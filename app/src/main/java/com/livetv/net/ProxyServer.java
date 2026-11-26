package com.livetv.net;

import android.content.Context;
import android.util.Log;

import com.livetv.player.HlsDvr;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简本地 HTTP 服务：给播放器提供带请求头的透传代理，以及 DVR 时移的播放列表/切片。
 * 只监听 127.0.0.1，随机端口，随进程存活。
 */
public final class ProxyServer {

    private static final String TAG = "LiveTVProxy";
    private static ProxyServer instance;

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Map<String, HlsDvr> dvrs = new ConcurrentHashMap<>();
    private ServerSocket serverSocket;
    private volatile boolean running;
    private int port;

    private ProxyServer() {
    }

    public static synchronized ProxyServer get(Context context) {
        if (instance == null) {
            ProxyServer server = new ProxyServer();
            server.start();
            instance = server;
        }
        return instance;
    }

    private void start() {
        try {
            serverSocket = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"));
            serverSocket.setReuseAddress(true);
            port = serverSocket.getLocalPort();
            running = true;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            }, "livetv-proxy");
            thread.setDaemon(true);
            thread.start();
            Log.i(TAG, "proxy started on " + port);
        } catch (IOException e) {
            Log.e(TAG, "proxy start failed", e);
            running = false;
        }
    }

    public int port() {
        return port;
    }

    public String registerDvr(HlsDvr dvr) {
        String token = UUID.randomUUID().toString().replace("-", "");
        dvr.token = token;
        dvrs.put(token, dvr);
        return "http://127.0.0.1:" + port + "/d/" + token + "/index.m3u8";
    }

    public void unregisterDvr(String token) {
        if (token != null) dvrs.remove(token);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(15000);
                pool.execute(new Handler(socket));
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept failed", e);
            }
        }
    }

    private final class Handler implements Runnable {

        private final Socket socket;

        Handler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                InputStream raw = new BufferedInputStream(socket.getInputStream());
                String requestLine = readLine(raw);
                if (requestLine == null) return;
                String[] parts = requestLine.split(" ");
                if (parts.length < 2) return;
                String method = parts[0];
                String target = parts[1];
                Map<String, String> requestHeaders = new HashMap<>();
                String line;
                while ((line = readLine(raw)) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        requestHeaders.put(line.substring(0, colon).trim().toLowerCase(Locale.US), line.substring(colon + 1).trim());
                    }
                }
                String path = target;
                String query = "";
                int q = target.indexOf('?');
                if (q >= 0) {
                    path = target.substring(0, q);
                    query = target.substring(q + 1);
                }
                if (path.startsWith("/d/")) {
                    serveDvr(socket, method, path.substring(3), query, requestHeaders);
                } else {
                    sendError(socket, 404, "Not Found");
                }
            } catch (Exception e) {
                Log.w(TAG, "request failed: " + e.getMessage());
            } finally {
                close(socket);
            }
        }
    }

    private void serveDvr(Socket socket, String method, String path, String query, Map<String, String> requestHeaders) throws IOException {
        String token = path;
        String action = "";
        int slash = path.indexOf('/');
        if (slash >= 0) {
            token = path.substring(0, slash);
            action = path.substring(slash + 1);
        }
        HlsDvr dvr = dvrs.get(token);
        if (dvr == null) {
            sendError(socket, 404, "No dvr session");
            return;
        }
        if (action.startsWith("index.m3u8")) {
            byte[] body = dvr.playlist().getBytes("UTF-8");
            sendBytes(socket, 200, "application/vnd.apple.mpegurl", body, method);
        } else if (action.startsWith("seg/")) {
            long seq;
            try {
                seq = Long.parseLong(action.substring(4));
            } catch (Exception e) {
                sendError(socket, 400, "Bad segment");
                return;
            }
            File file = dvr.segmentFile(seq);
            if (file == null || !file.exists()) {
                sendError(socket, 404, "Segment not ready");
                return;
            }
            sendFile(socket, file, method);
        } else if (action.startsWith("raw")) {
            String url = param(query, "u");
            if (url == null) {
                sendError(socket, 400, "Missing url");
                return;
            }
            // 仅允许 http/https，避免把本地代理当作 file/任意协议的 SSRF 跳板
            String lower = url.toLowerCase(Locale.US);
            if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
                sendError(socket, 400, "Unsupported url scheme");
                return;
            }
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", dvr.ua.isEmpty() ? Http.DEFAULT_UA : dvr.ua);
                connection.setRequestProperty("Accept-Encoding", "identity");
                for (Map.Entry<String, String> entry : dvr.headers.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
                String range = requestHeaders.get("range");
                if (range != null) connection.setRequestProperty("Range", range);
                int code = connection.getResponseCode();
                InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                if (input != null) {
                    copy(input, body);
                    input.close();
                }
                sendBytes(socket, code >= 400 ? code : 200, connection.getContentType(), body.toByteArray(), method);
            } finally {
                if (connection != null) connection.disconnect();
            }
        } else {
            sendError(socket, 404, "Not Found");
        }
    }

    private static String param(String query, String name) {
        if (query == null || query.isEmpty()) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if (name.equals(pair.substring(0, eq))) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    private void sendFile(Socket socket, File file, String method) throws IOException {
        long length = file.length();
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String head = "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: " + length
                + "\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        if (!"HEAD".equalsIgnoreCase(method)) {
            InputStream input = new FileInputStream(file);
            try {
                copy(input, out);
            } finally {
                input.close();
            }
        }
        out.flush();
    }

    /** 去掉 CR/LF，阻止上游 Content-Type 等响应头注入。 */
    private static String sanitizeHeader(String value) {
        if (value == null) return "";
        return value.replace("\r", "").replace("\n", "");
    }

    private void sendBytes(Socket socket, int code, String type, byte[] body, String method) throws IOException {
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(code).append(" OK\r\n");
        head.append("Content-Type: ").append(sanitizeHeader(type == null ? "application/octet-stream" : type)).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        // 不再对浏览器开放 CORS 通配，避免本机页面/DNS rebinding 跨域读取代理内容
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes("UTF-8"));
        if (!"HEAD".equalsIgnoreCase(method)) out.write(body);
        out.flush();
    }

    private void sendError(Socket socket, int code, String message) throws IOException {
        sendBytes(socket, code, "text/plain; charset=utf-8", message.getBytes("UTF-8"), "GET");
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = input.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buffer.write(b);
            if (buffer.size() > 8192) break;
        }
        if (b == -1 && buffer.size() == 0) return null;
        return buffer.toString("UTF-8");
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int length;
        while ((length = input.read(buffer)) != -1) {
            output.write(buffer, 0, length);
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }
}

