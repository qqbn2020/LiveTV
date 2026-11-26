package com.livetv.net;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public final class Http {

    public static final String DEFAULT_UA = "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final int TIMEOUT = 20000;
    private static final int MAX_BYTES = 32 * 1024 * 1024;

    private Http() {
    }

    public static String string(Context context, String url, String ua, Map<String, String> headers) throws IOException {
        byte[] data = bytes(context, url, ua, headers);
        String text = decode(data);
        // UTF-8 BOM 会让 trim()/startsWith("{") 判断失效，统一去掉
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        return text;
    }

    private static String decode(byte[] data) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException ignored) {
        }
        try {
            return Charset.forName("GB18030").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (Exception ignored) {
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    public static byte[] bytes(Context context, String url, String ua, Map<String, String> headers) throws IOException {
        if (url == null || url.trim().isEmpty()) throw new IOException("empty url");
        url = url.trim();
        if (url.startsWith("content://")) {
            InputStream input = context.getContentResolver().openInputStream(Uri.parse(url));
            if (input == null) throw new IOException("cannot open " + url);
            try {
                return read(input);
            } finally {
                close(input);
            }
        }
        if (url.startsWith("file://")) {
            return read(new FileInputStream(new File(Uri.parse(url).getPath())));
        }
        if (url.startsWith("/")) {
            return read(new FileInputStream(new File(url)));
        }
        return readNetwork(url, ua, headers);
    }

    private static byte[] readNetwork(String url, String ua, Map<String, String> headers) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT);
            connection.setReadTimeout(TIMEOUT);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", ua == null || ua.isEmpty() ? DEFAULT_UA : ua);
            connection.setRequestProperty("Accept-Encoding", "gzip");
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) continue;
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            int code = connection.getResponseCode();
            if (code >= 400) throw new IOException("HTTP " + code + " " + url);
            InputStream input = connection.getInputStream();
            try {
                byte[] data = read(input);
                return maybeGunzip(data);
            } finally {
                close(input);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] maybeGunzip(byte[] data) throws IOException {
        if (data.length < 2 || !(data[0] == (byte) 0x1F && data[1] == (byte) 0x8B)) return data;
        GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data));
        try {
            return read(gzip, MAX_BYTES);
        } finally {
            close(gzip);
        }
    }

    public static byte[] read(InputStream input) throws IOException {
        return read(input, MAX_BYTES);
    }

    private static byte[] read(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int length;
        while ((length = input.read(buffer)) != -1) {
            total += length;
            if (total > maxBytes) throw new IOException("content too large (> " + maxBytes + " bytes)");
            output.write(buffer, 0, length);
        }
        return output.toByteArray();
    }

    private static void close(InputStream input) {
        try {
            if (input != null) input.close();
        } catch (IOException ignored) {
        }
    }
}
