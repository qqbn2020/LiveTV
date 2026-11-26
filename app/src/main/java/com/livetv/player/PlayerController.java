package com.livetv.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import com.livetv.Store;
import com.livetv.model.Channel;
import com.livetv.model.EpgProgram;
import com.livetv.parser.CatchupUtil;

import java.util.Map;

/** 播放器门面：对外 API 保持不变，内部按需切换 ExoPlayer / IJK / mpv 内核。 */
public class PlayerController {

    public interface Listener {
        void onPlayingChanged(boolean playing);

        void onError(String message);
    }

    private final Context context;
    private final ViewGroup container;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private PlayerEngine engine;
    private int engineType;
    private Channel channel;
    private int index;
    private int retry;
    private boolean released;
    private String currentUrl = "";
    private HlsDvr dvr;
    private int generation;
    private int aspect;
    private float speed = 1f;
    private float volume = 1f;

    private final PlayerEngine.Callback engineCallback = new PlayerEngine.Callback() {
        @Override
        public void onPrepared() {
        }

        @Override
        public void onPlayingChanged(boolean playing) {
            if (!released) listener.onPlayingChanged(playing);
        }

        @Override
        public void onError(String message) {
            if (!released) handleError(message);
        }
    };

    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            start();
        }
    };

    public PlayerController(Context context, ViewGroup container, Listener listener) {
        this.context = context.getApplicationContext();
        this.container = container;
        this.listener = listener;
        createEngine();
        applyAspect(Store.aspect(context));
    }

    private void createEngine() {
        engineType = Store.playerEngine(context);
        engine = buildEngine(engineType);
        container.removeAllViews();
        View view = engine.view();
        container.addView(view, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (speed != 1f) engine.setSpeed(speed);
        if (volume != 1f) engine.setVolume(volume);
    }

    private PlayerEngine buildEngine(int type) {
        switch (type) {
            case 1:
                return new IjkEngine(context, engineCallback);
            case 2:
                return new MpvEngine(context, engineCallback);
            default:
                return new ExoEngine(context, engineCallback);
        }
    }

    public static boolean available(int type) {
        switch (type) {
            case 1:
                return IjkAvailable.get();
            case 2:
                return MpvAvailable.get();
            default:
                return true;
        }
    }

    /** 切换内核：重建引擎、清空播放状态，由调用方重新 play。 */
    public void switchEngine(int type) {
        if (released || engineType == type) return;
        handler.removeCallbacks(retryRunnable);
        generation++;
        closeDvr();
        try {
            engine.stop();
        } catch (Exception ignored) {
        }
        engine.release();
        createEngine();
        currentUrl = "";
    }

    public int engineType() {
        return engineType;
    }

    public Channel channel() {
        return channel;
    }

    public void play(Channel channel) {
        this.channel = channel;
        this.index = channel.lineIndex;
        this.retry = 0;
        handler.removeCallbacks(retryRunnable);
        start();
    }

    public void playCatchup(Channel channel, EpgProgram program) {
        String url = CatchupUtil.build(channel, program);
        if (url.isEmpty()) {
            listener.onError("该频道不支持回看");
            return;
        }
        this.channel = null;
        this.retry = 0;
        handler.removeCallbacks(retryRunnable);
        startUrl(url, channel.ua, channel.headers);
    }

    public void playUrl(String url, Channel channel) {
        this.channel = null;
        this.retry = 0;
        handler.removeCallbacks(retryRunnable);
        startUrl(url, channel == null ? "" : channel.ua, channel == null ? null : channel.headers);
    }

    private void start() {
        if (released || channel == null) return;
        if (channel.urls.isEmpty()) {
            listener.onError("没有可用的线路");
            return;
        }
        if (index < 0 || index >= channel.urls.size()) index = 0;
        channel.lineIndex = index;
        startUrl(channel.urls.get(index), channel.ua, channel.headers);
    }

    private void startUrl(String url, String ua, Map<String, String> headers) {
        if (url == null || url.trim().isEmpty()) {
            listener.onError("地址为空");
            return;
        }
        currentUrl = url;
        final int gen = ++generation;
        closeDvr();
        // 本地 DVR 时移依赖 ExoPlayer 的 EVENT 播放列表，其它内核直接连源
        boolean dvrEnabled = engineType == 0 && channel != null && Store.timeshift(context) && isHls(url);
        if (dvrEnabled) {
            android.util.Log.i("LiveTV", "dvr start url=" + url);
            HlsDvr.open(context, url, ua, headers, Store.timeshiftWindow(context), new HlsDvr.Listener() {
                @Override
                public void onReady(HlsDvr session, String playlistUrl) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (released || gen != generation) {
                                session.close();
                                return;
                            }
                            dvr = session;
                            android.util.Log.i("LiveTV", "dvr ready playlist=" + playlistUrl);
                            playMedia(playlistUrl, "", null);
                        }
                    });
                }

                @Override
                public void onFallback(HlsDvr session, String reason) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (released || gen != generation) {
                                session.close();
                                return;
                            }
                            android.util.Log.i("LiveTV", "dvr fallback " + reason);
                            session.close();
                            playMedia(url, ua, headers);
                        }
                    });
                }
            });
            return;
        }
        playMedia(url, ua, headers);
    }

    private boolean isHls(String url) {
        String lower = url.toLowerCase();
        return (lower.startsWith("http://") || lower.startsWith("https://"))
                && (lower.contains(".m3u8") || lower.contains("m3u8?"));
    }

    private void playMedia(String url, String ua, Map<String, String> headers) {
        android.util.Log.i("LiveTV", "play engine=" + engine.name() + " url=" + url);
        try {
            engine.prepare(url, ua, headers);
        } catch (Exception e) {
            listener.onError(e.getMessage() == null ? "播放失败" : e.getMessage());
        }
    }

    private void handleError(String message) {
        if (channel == null) {
            listener.onError(message);
            return;
        }
        handler.removeCallbacks(retryRunnable);
        if (index + 1 < channel.urls.size()) {
            index++;
            channel.lineIndex = index;
            retry = 0;
            handler.postDelayed(retryRunnable, 1000);
            return;
        }
        if (retry < 1) {
            retry++;
            handler.postDelayed(retryRunnable, 1500);
            return;
        }
        listener.onError(message);
    }

    public void pause() {
        engine.setPlayWhenReady(false);
    }

    public void resume() {
        engine.setPlayWhenReady(true);
    }

    public boolean isPlaying() {
        return engine.isPlaying();
    }

    public boolean isDvr() {
        return dvr != null;
    }

    public boolean isSeekable() {
        return engine.isSeekable();
    }

    public long duration() {
        return engine.duration();
    }

    public long position() {
        return engine.position();
    }

    public void seekTo(long positionMs) {
        if (!isSeekable()) return;
        engine.seekTo(Math.max(0, Math.min(duration(), positionMs)));
    }

    public void seekBack(long deltaMs) {
        if (!isSeekable()) return;
        seekTo(position() - deltaMs);
    }

    public void seekForward(long deltaMs) {
        if (!isSeekable()) return;
        seekTo(position() + deltaMs);
    }

    public void seekToLive() {
        if (dvr == null) return;
        long duration = duration();
        if (duration > 0) engine.seekTo(Math.max(0, duration));
    }

    /** 画面比例：0 适应 / 1 拉伸 / 2 裁剪。 */
    public void applyAspect(int aspect) {
        this.aspect = aspect;
        engine.setAspect(aspect);
    }

    public void setSpeed(float value) {
        speed = Math.max(0.25f, Math.min(4f, value));
        engine.setSpeed(speed);
    }

    public float speed() {
        return speed;
    }

    public void setVolume(float value) {
        volume = Math.max(0f, Math.min(1f, value));
        engine.setVolume(volume);
    }

    public float volume() {
        return volume;
    }

    /** 停止播放但保留内核实例，用于加载失败时清场。 */
    public void stop() {
        handler.removeCallbacks(retryRunnable);
        generation++;
        closeDvr();
        engine.stop();
    }

    private void closeDvr() {
        if (dvr != null) {
            dvr.close();
            dvr = null;
        }
    }

    public boolean hasChannel() {
        return channel != null;
    }

    public String currentUrl() {
        return currentUrl;
    }

    public void release() {
        released = true;
        generation++;
        closeDvr();
        handler.removeCallbacks(retryRunnable);
        engine.release();
        container.removeAllViews();
    }

    /** 内核可用性检测放在独立类，便于在无 native 库环境下降级。 */
    private static final class IjkAvailable {
        static boolean get() {
            try {
                Class.forName("tv.danmaku.ijk.media.player.IjkMediaPlayer");
                return true;
            } catch (Throwable e) {
                return false;
            }
        }
    }

    private static final class MpvAvailable {
        static boolean get() {
            try {
                Class.forName("dev.jdtech.mpv.MPVLib");
                return true;
            } catch (Throwable e) {
                return false;
            }
        }
    }
}
