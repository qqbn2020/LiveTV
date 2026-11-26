package com.livetv.player;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import com.livetv.Store;
import com.livetv.net.Http;

import java.util.HashMap;
import java.util.Map;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** 基于 Bilibili IJKPlayer 的内核（FFmpeg 软解 / MediaCodec 硬解可切）。 */
public class IjkEngine implements PlayerEngine {

    private final Context context;
    private final TextureView view;
    private final IjkMediaPlayer player;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Surface surface;
    private int videoWidth;
    private int videoHeight;
    private int aspect;
    private boolean released;

    public IjkEngine(Context context, final Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.view = new TextureView(context);
        this.player = new IjkMediaPlayer();

        configureOptions();
        player.setOnPreparedListener(new IMediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(IMediaPlayer mp) {
                if (!released) {
                    callback.onPrepared();
                    player.start();
                    callback.onPlayingChanged(true);
                }
            }
        });
        player.setOnErrorListener(new IMediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(IMediaPlayer mp, int what, int extra) {
                if (!released) callback.onError("IJK 错误 " + what + "/" + extra);
                return true;
            }
        });
        player.setOnCompletionListener(new IMediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(IMediaPlayer mp) {
                if (!released) callback.onPlayingChanged(false);
            }
        });
        player.setOnVideoSizeChangedListener(new IMediaPlayer.OnVideoSizeChangedListener() {
            @Override
            public void onVideoSizeChanged(IMediaPlayer mp, int width, int height, int sarNum, int sarDen) {
                videoWidth = width;
                videoHeight = height;
                applyMatrix();
            }
        });
        view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                surface = new Surface(texture);
                player.setSurface(surface);
                applyMatrix();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                applyMatrix();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                player.setSurface(null);
                if (surface != null) surface.release();
                surface = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            }
        });
    }

    private void configureOptions() {
        boolean soft = Store.decodeMode(context) == 1;
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", soft ? 0 : 1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "infinite-buffer", 0);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 15000);
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_flags", "prefer_tcp");
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48);
        int seconds = Math.max(2, Store.buffer(context));
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min pkt num", Math.max(4, seconds));
    }

    @Override
    public View view() {
        return view;
    }

    @Override
    public void prepare(String url, String ua, Map<String, String> headers) {
        Map<String, String> all = new HashMap<>();
        if (headers != null) all.putAll(headers);
        if (!all.containsKey("User-Agent")) {
            all.put("User-Agent", ua == null || ua.isEmpty() ? Http.DEFAULT_UA : ua);
        }
        player.reset();
        try {
            player.setDataSource(url, all);
            player.prepareAsync();
        } catch (Exception e) {
            callback.onError(e.getMessage() == null ? "IJK 打开失败" : e.getMessage());
        }
    }

    @Override
    public void setPlayWhenReady(boolean play) {
        try {
            if (play) {
                if (!player.isPlaying()) player.start();
            } else if (player.isPlaying()) {
                player.pause();
                callback.onPlayingChanged(false);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void stop() {
        try {
            player.stop();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void release() {
        released = true;
        handler.removeCallbacksAndMessages(null);
        try {
            player.stop();
        } catch (Exception ignored) {
        }
        player.release();
        if (surface != null) surface.release();
        surface = null;
    }

    @Override
    public boolean isPlaying() {
        try {
            return player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean isSeekable() {
        return duration() > 0;
    }

    @Override
    public long duration() {
        try {
            long d = player.getDuration();
            return d < 0 ? 0 : d;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public long position() {
        try {
            long p = player.getCurrentPosition();
            return p < 0 ? 0 : p;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public void seekTo(long positionMs) {
        if (!isSeekable()) return;
        try {
            player.seekTo(Math.max(0, Math.min(duration(), positionMs)));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void setAspect(int aspect) {
        this.aspect = aspect;
        applyMatrix();
    }

    private void applyMatrix() {
        if (videoWidth <= 0 || videoHeight <= 0 || view.getWidth() <= 0 || view.getHeight() <= 0) return;
        Matrix matrix = new Matrix();
        float sx = (float) view.getWidth() / videoWidth;
        float sy = (float) view.getHeight() / videoHeight;
        if (aspect == 1) {
            matrix.setScale(sx, sy);
        } else if (aspect == 2) {
            float scale = Math.max(sx, sy);
            matrix.setScale(scale, scale);
            matrix.postTranslate((view.getWidth() - videoWidth * scale) / 2f, (view.getHeight() - videoHeight * scale) / 2f);
        } else {
            float scale = Math.min(sx, sy);
            matrix.setScale(scale, scale);
            matrix.postTranslate((view.getWidth() - videoWidth * scale) / 2f, (view.getHeight() - videoHeight * scale) / 2f);
        }
        view.setTransform(matrix);
    }

    @Override
    public void setSpeed(float speed) {
        try {
            player.setSpeed(speed);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void setVolume(float volume) {
        try {
            float v = Math.max(0f, Math.min(1f, volume));
            player.setVolume(v, v);
        } catch (Exception ignored) {
        }
    }

    @Override
    public String name() {
        return "IJKPlayer";
    }
}
