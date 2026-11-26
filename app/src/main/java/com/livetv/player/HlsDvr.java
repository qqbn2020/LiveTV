package com.livetv.player;

import android.content.Context;
import android.util.Log;

import com.livetv.net.Http;
import com.livetv.net.ProxyServer;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HLS 直播本地时移（DVR）：
 * 轮询上游直播列表 -> 把切片落到本地 -> 生成可回拖的 EVENT 播放列表给 ExoPlayer。
 * 只处理明文 HLS；AES-128 加密或纯点播列表会回退到直连播放。
 */
public final class HlsDvr {

    private static final String TAG = "LiveTVDvr";
    private static final long MAX_CACHE_BYTES = 200L * 1024 * 1024;

    public interface Listener {
        void onReady(HlsDvr dvr, String playlistUrl);

        void onFallback(HlsDvr dvr, String reason);
    }

    private static final class Seg {
        final long seq;
        final double duration;
        final String url;
        final boolean discontinuity;
        final String mapUrl;
        File file;
        volatile boolean ready;
        volatile boolean failed;

        Seg(long seq, double duration, String url, boolean discontinuity, String mapUrl) {
            this.seq = seq;
            this.duration = duration;
            this.url = url;
            this.discontinuity = discontinuity;
            this.mapUrl = mapUrl;
        }
    }

    public String token;
    public final String ua;
    public final Map<String, String> headers;

    private final Context context;
    private final String sourceUrl;
    private final long windowMs;
    private final File dir;
    private final List<Seg> segments = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private final ExecutorService downloads = Executors.newFixedThreadPool(4);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile boolean ready;
    private volatile boolean fallback;
    private volatile String playlistUrl;
    private volatile String mediaUrl;
    private String mapUrl;
    private int targetDuration = 6;
    private long lastSeq = -1;
    private long firstSeq = -1;
    private long discoveredAt;

    private HlsDvr(Context context, String url, String ua, Map<String, String> headers, long windowMs) {
        this.context = context.getApplicationContext();
        this.sourceUrl = url;
        this.ua = ua == null ? "" : ua;
        this.headers = headers == null ? new HashMap<String, String>() : new HashMap<>(headers);
        this.windowMs = Math.max(60000, windowMs);
        this.dir = new File(this.context.getCacheDir(), "dvr_" + System.currentTimeMillis());
        this.dir.mkdirs();
    }

    public static void open(Context context, String url, String ua, Map<String, String> headers, long windowMs, Listener listener) {
        HlsDvr dvr = new HlsDvr(context, url, ua, headers, windowMs);
        dvr.start(listener);
    }

    private void start(final Listener listener) {
        playlistUrl = ProxyServer.get(context).registerDvr(this);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    init();
                    if (fallback) {
                        listener.onFallback(HlsDvr.this, "not-live");
                        return;
                    }
                    ready = true;
                    listener.onReady(HlsDvr.this, playlistUrl);
                    loop();
                } catch (Exception e) {
                    Log.w(TAG, "dvr init failed: " + e.getMessage());
                    fallback = true;
                    listener.onFallback(HlsDvr.this, e.getMessage() == null ? "init-failed" : e.getMessage());
                }
            }
        });
    }

    private void init() throws Exception {
        String text = Http.string(context, sourceUrl, ua, headers);
        for (int depth = 0; depth < 3 && text.contains("#EXT-X-STREAM-INF"); depth++) {
            String variant = pickVariant(text, mediaUrl == null ? sourceUrl : mediaUrl);
            if (variant == null) throw new IOException("no variant");
            mediaUrl = variant;
            text = Http.string(context, mediaUrl, ua, headers);
        }
        if (mediaUrl == null) mediaUrl = sourceUrl;
        if (text.contains("#EXT-X-ENDLIST")) {
            fallback = true;
            return;
        }
        if (text.contains("METHOD=AES-128") || text.contains("METHOD=SAMPLE-AES")) {
            fallback = true;
            return;
        }
        applyPlaylist(text, true);
        if (segments.isEmpty()) throw new IOException("empty playlist");
        for (Seg seg : segments) download(seg);
        long deadline = System.currentTimeMillis() + 12000;
        while (!closed.get() && System.currentTimeMillis() < deadline) {
            if (segments.isEmpty()) break;
            Seg first = segments.get(0);
            if (first.ready) break;
            if (first.failed) throw new IOException("first segment failed");
            sleep(100);
        }
        if (segments.isEmpty() || !segments.get(0).ready) throw new IOException("segment timeout");
    }

    private String pickVariant(String text, String base) {
        String best = null;
        long bestBandwidth = -1;
        long bandwidth = -1;
        for (String line : text.split("\\r?\\n")) {
            line = line.trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                bandwidth = parseLong(attribute(line, "BANDWIDTH"), -1);
            } else if (!line.isEmpty() && !line.startsWith("#") && bandwidth >= 0) {
                String url = resolve(base, line);
                if (bandwidth > bestBandwidth) {
                    bestBandwidth = bandwidth;
                    best = url;
                }
                bandwidth = -1;
            }
        }
        if (best == null) {
            for (String line : text.split("\\r?\\n")) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) return resolve(base, line);
            }
        }
        return best;
    }

    private void loop() {
        while (!closed.get() && !fallback) {
            try {
                Thread.sleep(Math.min(3000, Math.max(1500, targetDuration * 1000L / 2)));
                String text = Http.string(context, mediaUrl, ua, headers);
                if (text.contains("#EXT-X-ENDLIST")) {
                    // 直播结束：保留现有窗口，继续提供已缓存内容，不再追加。
                    continue;
                }
                applyPlaylist(text, false);
                prune();
            } catch (Exception e) {
                if (!closed.get()) Log.w(TAG, "poll failed: " + e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private synchronized void applyPlaylist(String text, boolean initial) throws IOException {
        long baseSeq = -1;
        double duration = -1;
        boolean discontinuity = false;
        String currentMap = mapUrl;
        int position = 0;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                baseSeq = parseLong(line.substring(line.indexOf(':') + 1).trim(), 0);
                continue;
            }
            if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                targetDuration = (int) Math.max(1, parseLong(line.substring(line.indexOf(':') + 1).trim(), 6));
                continue;
            }
            if (line.startsWith("#EXT-X-MAP:")) {
                String uri = attribute(line, "URI");
                if (uri != null && !uri.isEmpty()) {
                    currentMap = resolve(mediaUrl, uri);
                    mapUrl = currentMap;
                }
                continue;
            }
            if (line.startsWith("#EXT-X-DISCONTINUITY") && !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")) {
                discontinuity = true;
                continue;
            }
            if (line.startsWith("#EXTINF:")) {
                String value = line.substring(8);
                int comma = value.indexOf(',');
                if (comma >= 0) value = value.substring(0, comma);
                duration = parseDouble(value.trim(), targetDuration);
                continue;
            }
            if (line.startsWith("#")) continue;
            if (duration < 0) continue;
            long seq = baseSeq < 0 ? position : baseSeq + position;
            position++;
            String url = resolve(mediaUrl, line);
            if (seq <= lastSeq) continue;
            if (!seen.add(url)) continue;
            Seg seg = new Seg(seq, duration, url, discontinuity, currentMap);
            seg.file = new File(dir, "seg_" + seq);
            segments.add(seg);
            lastSeq = seq;
            if (firstSeq < 0) firstSeq = seq;
            if (initial) discoveredAt = System.currentTimeMillis();
            download(seg);
            duration = -1;
            discontinuity = false;
        }
    }

    private void download(final Seg seg) {
        downloads.execute(new Runnable() {
            @Override
            public void run() {
                if (closed.get() || seg.ready) return;
                File part = new File(seg.file.getAbsolutePath() + ".part");
                HttpURLConnection connection = null;
                InputStream input = null;
                BufferedOutputStream output = null;
                try {
                    connection = (HttpURLConnection) new URL(seg.url).openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(25000);
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestProperty("User-Agent", ua.isEmpty() ? Http.DEFAULT_UA : ua);
                    connection.setRequestProperty("Accept-Encoding", "identity");
                    for (Map.Entry<String, String> entry : headers.entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null) {
                            connection.setRequestProperty(entry.getKey(), entry.getValue());
                        }
                    }
                    int code = connection.getResponseCode();
                    if (code >= 400) throw new IOException("HTTP " + code);
                    input = connection.getInputStream();
                    output = new BufferedOutputStream(new FileOutputStream(part));
                    byte[] buffer = new byte[16 * 1024];
                    int length;
                    while ((length = input.read(buffer)) != -1) {
                        if (closed.get()) throw new IOException("dvr closed");
                        output.write(buffer, 0, length);
                    }
                    output.flush();
                    if (!part.renameTo(seg.file)) throw new IOException("rename failed");
                    seg.ready = true;
                } catch (Exception e) {
                    seg.failed = true;
                    Log.w(TAG, "segment " + seg.seq + " failed: " + e.getMessage());
                } finally {
                    try {
                        if (output != null) output.close();
                    } catch (Exception ignored) {
                    }
                    try {
                        if (input != null) input.close();
                    } catch (Exception ignored) {
                    }
                    if (connection != null) connection.disconnect();
                    if (!seg.ready) part.delete();
                }
            }
        });
    }

    private synchronized void prune() {
        if (segments.size() <= 3) return;
        long total = 0;
        long bytes = 0;
        for (Seg seg : segments) {
            total += (long) (seg.duration * 1000);
            if (seg.file != null && seg.file.exists()) bytes += seg.file.length();
        }
        int remove = 0;
        while (remove < segments.size() - 3 && (total > windowMs || bytes > MAX_CACHE_BYTES)) {
            Seg seg = segments.get(remove);
            total -= (long) (seg.duration * 1000);
            if (seg.file != null) {
                bytes -= seg.file.length();
                seg.file.delete();
            }
            seen.remove(seg.url);
            remove++;
        }
        if (remove > 0) segments.subList(0, remove).clear();
    }

    /** 生成给播放器的播放列表。只输出从队首开始的连续已下载片段，避免播放器看到缺口。 */
    public synchronized String playlist() {
        if (segments.isEmpty()) return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:" + targetDuration + "\n";
        int start = 0;
        while (start < segments.size() && !segments.get(start).ready) start++;
        if (start >= segments.size()) start = 0;
        boolean hasMap = mapUrl != null;
        for (int i = start; i < segments.size(); i++) {
            if (segments.get(i).mapUrl != null) {
                hasMap = true;
                break;
            }
        }
        StringBuilder builder = new StringBuilder();
        builder.append("#EXTM3U\n");
        builder.append("#EXT-X-VERSION:").append(hasMap ? 7 : 3).append('\n');
        builder.append("#EXT-X-TARGETDURATION:").append(targetDuration).append('\n');
        builder.append("#EXT-X-MEDIA-SEQUENCE:").append(segments.get(start).seq).append('\n');
        builder.append("#EXT-X-PLAYLIST-TYPE:EVENT\n");
        String lastMap = null;
        for (int i = start; i < segments.size(); i++) {
            Seg seg = segments.get(i);
            if (!seg.ready) break;
            if (seg.discontinuity) builder.append("#EXT-X-DISCONTINUITY\n");
            // 每个切片带自己的 MAP，避免 DISCONTINUITY 后 init segment 错配
            if (seg.mapUrl != null && !seg.mapUrl.equals(lastMap)) {
                builder.append("#EXT-X-MAP:URI=\"").append(rawUrl(seg.mapUrl)).append("\"\n");
                lastMap = seg.mapUrl;
            }
            builder.append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f", seg.duration)).append(",\n");
            builder.append("/d/").append(token).append("/seg/").append(seg.seq).append('\n');
        }
        return builder.toString();
    }

    private String rawUrl(String url) {
        try {
            return "/d/" + token + "/raw?u=" + URLEncoder.encode(url, "UTF-8");
        } catch (Exception e) {
            return url;
        }
    }

    public File segmentFile(long seq) {
        synchronized (this) {
            for (Seg seg : segments) {
                if (seg.seq == seq) return seg.ready ? seg.file : null;
            }
        }
        return null;
    }

    public boolean isReady() {
        return ready;
    }

    /** 供本地代理校验 raw 请求，禁止借 DVR 的 UA/Header 访问任意主机。 */
    public String host() {
        try {
            return new URL(mediaUrl == null ? sourceUrl : mediaUrl).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    public long startWallTime() {
        return discoveredAt;
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ProxyServer.get(context).unregisterDvr(token);
        worker.shutdownNow();
        downloads.shutdownNow();
        delete(dir);
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        file.delete();
    }

    private static String attribute(String line, String key) {
        String prefix = key + "=";
        int start = line.indexOf(prefix);
        if (start < 0) {
            prefix = key.toLowerCase(java.util.Locale.US) + "=";
            start = line.toLowerCase(java.util.Locale.US).indexOf(prefix);
            if (start < 0) return null;
        }
        start += prefix.length();
        if (start >= line.length()) return null;
        char quote = line.charAt(start);
        if (quote == '"') {
            int end = line.indexOf('"', start + 1);
            return end < 0 ? null : line.substring(start + 1, end);
        }
        int end = line.indexOf(',', start);
        return end < 0 ? line.substring(start) : line.substring(start, end);
    }

    private static String resolve(String base, String relative) {
        try {
            return new URL(new URL(base), relative).toString();
        } catch (Exception e) {
            return relative;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
