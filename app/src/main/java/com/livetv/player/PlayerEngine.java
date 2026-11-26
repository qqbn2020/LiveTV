package com.livetv.player;

import android.view.View;

import java.util.Map;

/**
 * 播放内核抽象。ExoPlayer / IJK / mpv 各自实现，PlayerController 作为门面，
 * 负责线路降级、DVR 时移、生命周期等与内核无关的逻辑。
 */
public interface PlayerEngine {

    interface Callback {
        void onPrepared();

        void onPlayingChanged(boolean playing);

        void onError(String message);
    }

    /** 渲染视图，由控制器加入界面容器。 */
    View view();

    /** 异步准备播放。ua / headers 用于带鉴权的直播源。 */
    void prepare(String url, String ua, Map<String, String> headers);

    void setPlayWhenReady(boolean play);

    void stop();

    void release();

    boolean isPlaying();

    boolean isSeekable();

    long duration();

    long position();

    void seekTo(long positionMs);

    void setAspect(int aspect);

    void setSpeed(float speed);

    void setVolume(float volume);

    String name();
}
