package com.livetv.player;

import android.content.Context;
import android.net.Uri;
import android.view.View;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.rtmp.RtmpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.livetv.Store;
import com.livetv.net.Http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 基于 Media3 ExoPlayer 的内核。 */
public class ExoEngine implements PlayerEngine {

    private final Context context;
    private final ExoPlayer player;
    private final PlayerView view;
    private final Callback callback;

    private static final MediaCodecSelector PREFER_SOFTWARE = new MediaCodecSelector() {
        @Override
        public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder) throws MediaCodecUtil.DecoderQueryException {
            List<MediaCodecInfo> infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
            if (!mimeType.startsWith("video/")) return infos;
            List<MediaCodecInfo> sorted = new ArrayList<>(infos);
            Collections.sort(sorted, new Comparator<MediaCodecInfo>() {
                @Override
                public int compare(MediaCodecInfo a, MediaCodecInfo b) {
                    if (a.softwareOnly == b.softwareOnly) return 0;
                    return a.softwareOnly ? -1 : 1;
                }
            });
            return sorted;
        }
    };

    public ExoEngine(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        int seconds = Math.max(2, Store.buffer(context));
        int min = Math.max(4000, seconds * 1000 / 2);
        int max = Math.min(60000, Math.max(min + 6000, seconds * 1000 * 3));
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(min, max, Math.min(2000, min), Math.min(3000, min))
                .build();
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this.context).setEnableDecoderFallback(true);
        if (Store.decodeMode(this.context) == 1) renderers.setMediaCodecSelector(PREFER_SOFTWARE);
        player = new ExoPlayer.Builder(this.context).setRenderersFactory(renderers).setLoadControl(loadControl).build();
        view = new PlayerView(context);
        view.setUseController(false);
        // 不显示 Media3 自带缓冲圈，播放界面只保留视频画面。
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
        view.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                callback.onPlayingChanged(isPlaying);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                callback.onError(error.getErrorCodeName());
            }
        });
    }

    @Override
    public View view() {
        return view;
    }

    @Override
    public void prepare(String url, String ua, Map<String, String> headers) {
        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(url));
        String lower = url.toLowerCase();
        if (lower.contains(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if (lower.contains(".mpd")) item.setMimeType(MimeTypes.APPLICATION_MPD);
        player.setMediaSource(buildSource(item.build(), url, ua, headers));
        player.prepare();
        player.setPlayWhenReady(true);
        callback.onPrepared();
    }

    private MediaSource buildSource(MediaItem item, String url, String ua, Map<String, String> headers) {
        if (url.startsWith("rtmp")) {
            return new ProgressiveMediaSource.Factory(new RtmpDataSource.Factory()).createMediaSource(item);
        }
        if (url.startsWith("rtsp")) {
            return new RtspMediaSource.Factory().createMediaSource(item);
        }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(ua == null || ua.isEmpty() ? Http.DEFAULT_UA : ua)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
                .setAllowCrossProtocolRedirects(true);
        if (headers != null && !headers.isEmpty()) http.setDefaultRequestProperties(headers);
        DefaultDataSource.Factory dataSource = new DefaultDataSource.Factory(context, http);
        return new DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource).createMediaSource(item);
    }

    @Override
    public void setPlayWhenReady(boolean play) {
        player.setPlayWhenReady(play);
    }

    @Override
    public void stop() {
        player.stop();
    }

    @Override
    public void release() {
        view.setPlayer(null);
        player.release();
    }

    @Override
    public boolean isPlaying() {
        return player.isPlaying();
    }

    @Override
    public boolean isSeekable() {
        return player.isCurrentMediaItemSeekable() && player.getDuration() > 0;
    }

    @Override
    public long duration() {
        long duration = player.getDuration();
        return duration < 0 ? 0 : duration;
    }

    @Override
    public long position() {
        long position = player.getCurrentPosition();
        return position < 0 ? 0 : position;
    }

    @Override
    public void seekTo(long positionMs) {
        player.seekTo(Math.max(0, Math.min(duration(), positionMs)));
    }

    @Override
    public void setAspect(int aspect) {
        if (aspect == 1) view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
        else if (aspect == 2) view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        else view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
    }

    @Override
    public void setSpeed(float speed) {
        player.setPlaybackParameters(new androidx.media3.common.PlaybackParameters(speed));
    }

    @Override
    public void setVolume(float volume) {
        player.setVolume(Math.max(0f, Math.min(1f, volume)));
    }

    @Override
    public String name() {
        return "ExoPlayer";
    }
}
