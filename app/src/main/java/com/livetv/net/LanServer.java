package com.livetv.net;

import com.livetv.model.Channel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 局域网播放服务：电视端开启后，手机/电脑浏览器访问 http://电视IP:8899 即可看到频道列表并播放，
 * 也可把 http://电视IP:8899/playlist.m3u 作为直播源导入其他播放器。
 */
public final class LanServer {

    public static final int PORT = 8899;

    private static LanServer instance;

    private static final int MAX_CONNECTIONS = 24;
    private static final int SERVER_BACKLOG = 32;

    private ServerSocket server;
    private volatile boolean running;
    private volatile List<Channel> channels = new ArrayList<>();
    private volatile String epgUrl = "";
    // 有界线程池，避免恶意并发连接把 cachedThreadPool 撑爆
    private final ExecutorService pool = new ThreadPoolExecutor(
            2, MAX_CONNECTIONS, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(MAX_CONNECTIONS * 2),
            new ThreadPoolExecutor.AbortPolicy());

    public static synchronized LanServer get() {
        if (instance == null) instance = new LanServer();
        return instance;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized void start(List<Channel> list) {
        update(list);
        if (running) return;
        try {
            server = new ServerSocket(PORT, SERVER_BACKLOG);
            running = true;
        } catch (IOException e) {
            running = false;
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        final Socket socket = server.accept();
                        socket.setSoTimeout(8000);
                        try {
                            pool.execute(new Runnable() {
                                @Override
                                public void run() {
                                    handle(socket);
                                }
                            });
                        } catch (RuntimeException rejected) {
                            // 并发已满，直接关闭该连接，不影响 accept 循环继续服务
                            try {
                                socket.close();
                            } catch (IOException ignored) {
                            }
                        }
                    } catch (IOException e) {
                        break;
                    }
                }
            }
        }, "lan-accept").start();
    }

    public void update(List<Channel> list) {
        channels = list == null ? new ArrayList<Channel>() : new ArrayList<>(list);
    }

    public void setEpgUrl(String url) {
        epgUrl = url == null ? "" : url.trim();
    }

    public synchronized void stop() {
        running = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
            server = null;
        }
    }

    public static String address() {
        String ip = lanIp();
        return ip == null ? null : "http://" + ip + ":" + PORT;
    }

    private static String lanIp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void handle(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String request = reader.readLine();
            if (request == null) return;
            String[] parts = request.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            // 仅允许 GET；只解析路径，丢弃 fragment
            int hash = path.indexOf('#');
            if (hash >= 0) path = path.substring(0, hash);
            if (path.startsWith("/playlist.m3u")) {
                send(socket, 200, "audio/x-mpegurl; charset=utf-8", m3u().getBytes("UTF-8"));
                return;
            }
            if (path.startsWith("/play")) {
                int index = indexOf(path);
                Channel channel = channelAt(index);
                if (channel == null || channel.url().isEmpty()) {
                    send(socket, 404, "text/plain; charset=utf-8", "频道不存在".getBytes("UTF-8"));
                } else {
                    send(socket, 302, "text/plain; charset=utf-8", new byte[0], channel.url());
                }
                return;
            }
            String html = page();
            send(socket, 200, "text/html; charset=utf-8", html.getBytes("UTF-8"));
        } catch (Exception ignored) {
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 只解析 query 中名为 i 的参数，避免在整条路径里误匹配 'i='。 */
    private int indexOf(String path) {
        int q = path.indexOf('?');
        if (q < 0) return -1;
        String query = path.substring(q + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key;
            try {
                key = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
            } catch (Exception e) {
                continue;
            }
            if (!"i".equals(key)) continue;
            try {
                return Integer.parseInt(URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (Exception e) {
                return -1;
            }
        }
        return -1;
    }

    private Channel channelAt(int index) {
        List<Channel> list = channels;
        if (index < 0 || index >= list.size()) return null;
        return list.get(index);
    }

    private String m3u() {
        StringBuilder sb = new StringBuilder("#EXTM3U");
        if (!epgUrl.isEmpty()) sb.append(" x-tvg-url=\"").append(escape(epgUrl)).append('"');
        sb.append('\n');
        for (Channel channel : channels) {
            if (channel.url().isEmpty()) continue;
            sb.append("#EXTINF:-1 tvg-name=\"").append(escape(channel.tvgName.isEmpty() ? channel.name : channel.tvgName))
                    .append("\" tvg-logo=\"").append(escape(channel.logo))
                    .append("\" group-title=\"").append(escape(channel.group)).append("\",")
                    .append(channel.name).append('\n').append(channel.url()).append('\n');
        }
        return sb.toString();
    }

    private String page() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>LiveTV 频道列表</title><style>")
                .append("body{margin:0;background:#101318;color:#e8ecf1;font:15px/1.6 -apple-system,'PingFang SC','Microsoft YaHei',sans-serif}")
                .append("header{padding:18px 16px;background:#171c24;position:sticky;top:0}")
                .append("h1{margin:0;font-size:19px}small{color:#8a94a3}")
                .append(".g{padding:14px 16px 4px;color:#6fa8ff;font-size:13px}")
                .append("a{display:flex;align-items:center;gap:12px;padding:12px 16px;color:#e8ecf1;text-decoration:none;border-bottom:1px solid #1d232c}")
                .append("a:active{background:#1b2230}.n{color:#6b7686;font-size:12px;width:34px}")
                .append(".t{flex:1}.tip{padding:16px;color:#8a94a3;font-size:12px}")
                .append("img{width:30px;height:30px;object-fit:contain}</style></head><body>")
                .append("<header><h1>LiveTV 频道列表</h1><small>共 ").append(channels.size()).append(" 个频道 · 点击即可播放</small></header>");
        String group = null;
        int index = 0;
        for (Channel channel : channels) {
            int i = index++;
            if (channel.url().isEmpty()) continue;
            if (!channel.group.equals(group)) {
                group = channel.group;
                sb.append("<div class=\"g\">").append(escape(group)).append("</div>");
            }
            sb.append("<a href=\"/play?i=").append(i).append("\">")
                    .append(channel.number.isEmpty() ? "" : "<span class=\"n\">" + escape(channel.number) + "</span>")
                    .append("<span class=\"t\">").append(escape(channel.name)).append("</span></a>");
        }
        sb.append("<div class=\"tip\">外部播放器可导入：http://").append(lanIp()).append(':').append(PORT)
                .append("/playlist.m3u</div></body></html>");
        return sb.toString();
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** 去掉 CR/LF，阻止恶意直播源地址注入任意响应头。 */
    private static String sanitizeHeader(String value) {
        if (value == null) return "";
        return value.replace("\r", "").replace("\n", "");
    }

    private static void send(Socket socket, int code, String type, byte[] body) throws IOException {
        send(socket, code, type, body, null);
    }

    private static void send(Socket socket, int code, String type, byte[] body, String location) throws IOException {
        OutputStream out = socket.getOutputStream();
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(code).append(' ').append(code == 200 ? "OK" : code == 302 ? "Found" : "Not Found").append("\r\n");
        head.append("Content-Type: ").append(type).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Cache-Control: no-store\r\n");
        if (location != null) head.append("Location: ").append(sanitizeHeader(location)).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes("UTF-8"));
        if (body.length > 0) out.write(body);
        out.flush();
    }
}
