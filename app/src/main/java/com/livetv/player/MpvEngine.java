package com.livetv.player;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import com.livetv.Store;
import com.livetv.net.Http;

import java.util.Locale;
import java.util.Map;

import dev.jdtech.mpv.MPVLib;

/** 基于 libmpv 的内核（FFmpeg 软解 + libass 字幕，命令式 API）。 */
public class MpvEngine implements PlayerEngine, MPVLib.EventObserver {

    private final Context context;
    private final TextureView view;
    private final MPVLib mpv = MPVLib.INSTANCE;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Surface surface;
    private boolean released;
    private boolean prepared;
    // time-pos/duration 在文件刚加载时可能是 unavailable，此时读取会让 mpv 每帧打印
    // "property unavailable" 错误。只有当 observeProperty 回调真正送值后才认为可读。
    private boolean timePosReady;
    private boolean durationReady;
    private long watchLaterMs = -1;
    private int aspect;

    public MpvEngine(Context context, final Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.view = new TextureView(context);

        try {
            mpv.create(this.context);
            mpv.init();
            mpv.setOptionString("vo", "gpu");
            mpv.setOptionString("hwdec", Store.decodeMode(context) == 1 ? "no" : "mediacodec");
            mpv.setOptionString("audio", "auto");
            mpv.setOptionString("Keep-open", "no");
            mpv.setOptionString("network-timeout", "15");
            mpv.setOptionString("cache-secs", String.valueOf(Math.max(2, Store.buffer(context))));
            // 直播时保持画面干净：关闭 mpv 自带 OSD/屏幕控制器，不叠加文件名、进度、时间。
            mpv.setOptionString("osd-level", "0");
            mpv.setOptionString("osc", "no");
            mpv.setOptionString("osd-bar", "no");
            mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
            mpv.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
            mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
            MPVLib.addObserver(this);
        } catch (Throwable e) {
            callback.onError("mpv 初始化失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }

        view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                surface = new Surface(texture);
                try {
                    mpv.attachSurface(surface);
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                try {
                    mpv.detachSurface();
                } catch (Throwable ignored) {
                }
                if (surface != null) surface.release();
                surface = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            }
        });
    }

    @Override
    public View view() {
        return view;
    }

    @Override
    public void prepare(String url, String ua, Map<String, String> headers) {
        prepared = false;
        timePosReady = false;
        durationReady = false;
        try {
            String userAgent = ua == null || ua.isEmpty() ? Http.DEFAULT_UA : ua;
            mpv.setOptionString("user-agent", userAgent);
            mpv.setOptionString("http-header-fields", headers == null ? "" : joinHeaders(headers));
            mpv.setOptionString("network-timeout", "15");
            mpv.command(new String[]{"loadfile", url, "replace"});
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!released && !prepared) {
                        callback.onPrepared();
                        prepared = true;
                    }
                }
            }, 400);
        } catch (Throwable e) {
            callback.onError(e.getMessage() == null ? "mpv 打开失败" : e.getMessage());
        }
    }

    private String joinHeaders(Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(entry.getKey()).append(": ").append(entry.getValue());
        }
        return sb.toString();
    }

    @Override
    public void setPlayWhenReady(boolean play) {
        try {
            mpv.command(new String[]{"set", "pause", play ? "no" : "yes"});
            callback.onPlayingChanged(play);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void stop() {
        try {
            mpv.command(new String[]{"stop"});
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void release() {
        released = true;
        handler.removeCallbacksAndMessages(null);
        try {
            MPVLib.removeObserver(this);
            mpv.detachSurface();
            mpv.destroy();
        } catch (Throwable ignored) {
        }
        if (surface != null) surface.release();
        surface = null;
    }

    @Override
    public boolean isPlaying() {
        if (!prepared) return false;
        Boolean paused = mpv.getPropertyBoolean("pause");
        return paused != null ? !paused : false;
    }

    @Override
    public boolean isSeekable() {
        return duration() > 0;
    }

    @Override
    public long duration() {
        if (!prepared || !durationReady) return 0;
        Double d = mpv.getPropertyDouble("duration");
        return d == null || d < 0 ? 0 : (long) (d * 1000);
    }

    @Override
    public long position() {
        if (!prepared || !timePosReady) return 0;
        Double p = mpv.getPropertyDouble("time-pos");
        return p == null || p < 0 ? 0 : (long) (p * 1000);
    }

    @Override
    public void seekTo(long positionMs) {
        if (!isSeekable()) return;
        try {
            double seconds = positionMs / 1000.0;
            mpv.command(new String[]{"seek", String.format(Locale.US, "%.3f", seconds), "absolute+exact"});
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void setAspect(int aspect) {
        this.aspect = aspect;
        try {
            String value = aspect == 1 ? "16:9" : aspect == 2 ? "16:9" : "auto";
            mpv.setOptionString("video-aspect-override", value);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void setSpeed(float speed) {
        try {
            mpv.setPropertyDouble("speed", speed);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void setVolume(float volume) {
        try {
            mpv.setPropertyInt("volume", Math.round(Math.max(0f, Math.min(1f, volume)) * 100));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String name() {
        return "mpv";
    }

    @Override
    public void eventProperty(String property) {
    }

    @Override
    public void eventProperty(String property, long value) {
    }

    @Override
    public void eventProperty(String property, double value) {
        // 属性真正送值时才标记可读，避免加载窗口期反复读取导致 mpv 刷 "property unavailable" 错误
        if ("time-pos".equals(property)) timePosReady = true;
        else if ("duration".equals(property)) durationReady = true;
    }

    @Override
    public void eventProperty(String property, boolean value) {
        if ("pause".equals(property) && !released) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (!released) callback.onPlayingChanged(!value);
                }
            });
        }
    }

    @Override
    public void eventProperty(String property, String value) {
    }

    @Override
    public void endFile(int reason, int reasonForError) {
        if (!released && reason == MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_ERROR) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (!released) callback.onError("mpv 播放失败");
                }
            });
        }
    }

    @Override
    public void event(int event) {
        if (event == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED && !released) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (released) return;
                    prepared = true;
                    callback.onPrepared();
                    if (watchLaterMs > 0) {
                        long target = watchLaterMs;
                        watchLaterMs = -1;
                        seekTo(target);
                    }
                }
            });
        }
    }
}
