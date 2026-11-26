package com.livetv.parser;

import android.util.Xml;

import com.livetv.model.Channel;
import com.livetv.model.Group;
import com.livetv.model.LiveSource;
import com.livetv.model.Playlist;
import com.livetv.util.Fmt;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PlaylistParser {

    private static final Pattern URL = Pattern.compile("(\\w+)://");
    private static final String DEFAULT_GROUP = "默认";

    private PlaylistParser() {
    }

    public static Playlist parse(String text, String sourceUrl) throws Exception {
        Playlist playlist = new Playlist();
        if (text == null) text = "";
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return playlist;
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) json(playlist, trimmed);
        else if (trimmed.startsWith("<")) xml(playlist, trimmed);
        else if (trimmed.contains("#EXTM3U")) m3u(playlist, trimmed);
        else txt(playlist, trimmed);
        normalize(playlist);
        return playlist;
    }

    // ------------------------------------------------------------------ normalize

    private static void normalize(Playlist playlist) {
        int number = 0;
        List<Group> empty = new ArrayList<>();
        for (Group group : playlist.groups) {
            if (group.isEmpty()) {
                empty.add(group);
                continue;
            }
            for (Channel channel : group.channels) {
                channel.group = group.name;
                number++;
                if (channel.number == null || channel.number.trim().isEmpty()) channel.number = Fmt.number(number);
            }
        }
        playlist.groups.removeAll(empty);
    }

    // ------------------------------------------------------------------ m3u

    private static void m3u(Playlist playlist, String text) {
        Pending pending = new Pending();
        String globalSource = "";
        String globalType = "";
        String globalReplace = "";
        Channel channel = null;

        String[] lines = text.replace("\r", "").split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTM3U")) {
                globalType = attr(line, "catchup");
                globalSource = attr(line, "catchup-source");
                globalReplace = attr(line, "catchup-replace");
                if (playlist.epgUrl.isEmpty()) {
                    String epg = attr(line, "tvg-url");
                    if (epg.isEmpty()) epg = attr(line, "url-tvg");
                    if (epg.isEmpty()) epg = attr(line, "x-tvg-url");
                    playlist.epgUrl = epg;
                }
                continue;
            }
            if (line.startsWith("#EXTINF:")) {
                String name = nameOf(line);
                if (name.isEmpty()) {
                    channel = null;
                    continue;
                }
                String groupName = attr(line, "group-title");
                Group group = playlist.group(groupName.isEmpty() ? DEFAULT_GROUP : groupName);
                channel = group.findOrCreate(name);
                String ua = attr(line, "http-user-agent");
                if (!ua.isEmpty()) channel.ua = ua;
                String tvgId = attr(line, "tvg-id");
                if (!tvgId.isEmpty()) channel.tvgId = tvgId;
                String tvgName = attr(line, "tvg-name");
                if (!tvgName.isEmpty()) channel.tvgName = tvgName;
                String logo = attr(line, "tvg-logo");
                if (!logo.isEmpty()) channel.logo = logo;
                String danmaku = attr(line, "danmaku");
                if (danmaku.isEmpty()) danmaku = attr(line, "danmaku-url");
                if (!danmaku.isEmpty()) channel.danmaku = danmaku;
                String chno = attr(line, "tvg-chno");
                if (!chno.isEmpty()) channel.number = chno;
                String channelCatchup = attr(line, "catchup");
                if (!channelCatchup.isEmpty()) channel.catchupType = channelCatchup;
                String channelSource = attr(line, "catchup-source");
                if (!channelSource.isEmpty()) channel.catchupSource = channelSource;
                String channelReplace = attr(line, "catchup-replace");
                if (!channelReplace.isEmpty()) channel.catchupReplace = channelReplace;
                continue;
            }
            if (line.startsWith("#EXTVLCOPT:") || line.startsWith("#EXTHTTP:") || line.startsWith("#KODIPROP:")) {
                pending.apply(line);
                continue;
            }
            if (line.startsWith("#")) continue;
            if (channel == null || !URL.matcher(line).find()) continue;

            String[] parts = line.split("\\|", 2);
            if (parts.length > 1) pending.parseHeaders(parts[1]);
            channel.addUrl(parts[0]);
            applySettings(channel, pending, globalType, globalSource, globalReplace);
            pending.reset();
        }
    }

    private static void applySettings(Channel channel, Pending pending, String type, String source, String replace) {
        if (pending.ua != null) channel.ua = pending.ua;
        if (pending.referer != null) channel.headers.put("Referer", pending.referer);
        if (pending.cookie != null) channel.headers.put("Cookie", pending.cookie);
        channel.headers.putAll(pending.headers);
        if (channel.catchupSource.isEmpty() && !source.isEmpty()) channel.catchupSource = source;
        if (channel.catchupType.isEmpty() && !type.isEmpty()) channel.catchupType = type;
        if (channel.catchupReplace.isEmpty() && !replace.isEmpty()) channel.catchupReplace = replace;
    }

    // ------------------------------------------------------------------ txt

    private static void txt(Playlist playlist, String text) {
        Pending pending = new Pending();
        Group group = null;

        String[] lines = text.replace("\r", "").split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("//")) continue;
            if (line.contains("#genre#")) {
                String name = line.split(",", 2)[0].trim();
                group = playlist.group(name.isEmpty() ? DEFAULT_GROUP : name);
                continue;
            }
            if (pending.find(line)) {
                pending.apply(line);
                continue;
            }
            if (line.startsWith("#")) continue;
            String[] parts = line.split(",", 2);
            if (parts.length < 2) continue;
            String name = parts[0].trim();
            String value = parts[1].trim();
            if (name.isEmpty() || !URL.matcher(value).find()) continue;
            if (group == null) group = playlist.group(DEFAULT_GROUP);
            Channel channel = group.findOrCreate(name);
            for (String item : value.split("#")) {
                if (item.trim().isEmpty()) continue;
                String[] splits = item.split("\\|", 2);
                if (splits.length > 1) pending.parseHeaders(splits[1]);
                if (URL.matcher(splits[0]).find()) channel.addUrl(splits[0].trim());
            }
            applySettings(channel, pending, "", "", "");
            pending.reset();
        }
    }

    // ------------------------------------------------------------------ xml

    private static void xml(Playlist playlist, String text) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(new StringReader(text));

        Group group = null;
        Channel channel = null;
        boolean inUrls = false;
        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                if ("group".equalsIgnoreCase(tag)) {
                    String name = value(parser, "title");
                    if (name.isEmpty()) name = value(parser, "name");
                    group = playlist.group(name.isEmpty() ? DEFAULT_GROUP : name);
                } else if ("channel".equalsIgnoreCase(tag)) {
                    String name = value(parser, "name");
                    String id = value(parser, "id");
                    if (group == null) group = playlist.group(DEFAULT_GROUP);
                    if (!name.isEmpty()) {
                        channel = group.findOrCreate(name);
                        if (!id.isEmpty()) channel.tvgId = id;
                        String logo = value(parser, "logo");
                        if (!logo.isEmpty()) channel.logo = logo;
                        String ua = value(parser, "ua");
                        if (!ua.isEmpty()) channel.ua = ua;
                        String number = value(parser, "number");
                        if (!number.isEmpty()) channel.number = number;
                        String catchupSource = value(parser, "catchup");
                        if (!catchupSource.isEmpty()) {
                            channel.catchupSource = catchupSource;
                            channel.catchupType = "default";
                        }
                    }
                } else if ("urls".equalsIgnoreCase(tag)) {
                    // 容器节点：不要调用 nextText()，否则会吞掉/抛错 <url> 子节点
                    inUrls = true;
                } else if ("url".equalsIgnoreCase(tag) && channel != null) {
                    String url = parser.nextText().trim();
                    channel.addUrl(url);
                }
            } else if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) {
                // 兼容 <urls>http://a http://b</urls> 这种纯文本写法
                if (inUrls && channel != null) {
                    String value = parser.getText().trim();
                    if (!value.isEmpty()) {
                        for (String url : value.split("\\s+")) channel.addUrl(url);
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                String tag = parser.getName();
                if ("urls".equalsIgnoreCase(tag)) inUrls = false;
                else if ("channel".equalsIgnoreCase(tag)) channel = null;
            }
        }
    }

    private static String value(XmlPullParser parser, String name) {
        String value = parser.getAttributeValue(null, name);
        return value == null ? "" : value.trim();
    }

    /** catchup 既可能是对象，也可能是 JSON 字符串（FongMi 源常见写法）。 */
    private static JSONObject catchupOf(JSONObject object) {
        JSONObject catchup = object.optJSONObject("catchup");
        if (catchup != null) return catchup;
        String text = object.optString("catchup", "").trim();
        if (text.startsWith("{")) {
            try {
                return new JSONObject(text);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    // ------------------------------------------------------------------ json

    private static void json(Playlist playlist, String text) throws Exception {
        String trimmed = text.trim();
        if (trimmed.startsWith("[")) {
            parseGroups(playlist, new JSONArray(trimmed));
            return;
        }
        JSONObject root = new JSONObject(trimmed);
        JSONArray lives = root.optJSONArray("lives");
        if (lives != null) {
            for (int i = 0; i < lives.length(); i++) {
                JSONObject item = lives.optJSONObject(i);
                if (item == null) continue;
                String name = item.optString("name", "线路" + (i + 1));
                String url = item.optString("url", "");
                String api = item.optString("api", "");
                String jar = item.optString("jar", "");
                String js = item.optString("js", "");
                String py = item.optString("py", "");
                String ext = item.optString("ext", "");
                String epg = item.optString("epg", "");
                String plugin = firstNonEmpty(jar, js, py, api);

                JSONArray itemGroups = item.optJSONArray("groups");
                if (itemGroups != null) {
                    Playlist temp = new Playlist();
                    temp.epgUrl = epg;
                    parseGroups(temp, itemGroups);
                    applyLiveDefaults(temp, item);
                    mergeGroups(playlist, temp);
                }

                JSONArray itemUrls = item.optJSONArray("urls");
                if (itemUrls != null && url.isEmpty()) {
                    addUrlSources(playlist, itemUrls, item);
                }

                boolean httpApi = api.startsWith("http://") || api.startsWith("https://");
                if (!url.isEmpty()) {
                    LiveSource source = new LiveSource(name, url);
                    source.epg = epg;
                    source.ua = item.optString("ua", "");
                    playlist.children.add(source);
                    if (playlist.epgUrl.isEmpty()) playlist.epgUrl = source.epg;
                    if (!plugin.isEmpty()) playlist.warnings.add(name + " 是插件源，已按直链尝试");
                } else if (httpApi) {
                    LiveSource source = new LiveSource(name, api);
                    source.epg = epg;
                    source.ua = item.optString("ua", "");
                    playlist.children.add(source);
                    if (playlist.epgUrl.isEmpty()) playlist.epgUrl = source.epg;
                } else if (!plugin.isEmpty()) {
                    playlist.warnings.add(name + " 需要插件运行（" + plugin + "），本版本未支持");
                } else if (itemGroups == null && itemUrls == null) {
                    // ext 是 FongMi 源上常见的附加参数，至少要在警告里保留可诊断信息
                    playlist.warnings.add(name + " 没有可用地址" + (ext.isEmpty() ? "" : "（ext=" + ext + "）"));
                }
            }
        }
        JSONArray urls = root.optJSONArray("urls");
        if (urls != null) addUrlSources(playlist, urls, root);
        JSONArray groups = root.optJSONArray("groups");
        if (groups != null) parseGroups(playlist, groups);
        JSONArray channels = root.optJSONArray("channels");
        if (channels == null) channels = root.optJSONArray("channel");
        if (channels != null) {
            String name = root.optString("name", root.optString("group", DEFAULT_GROUP));
            parseChannelArray(playlist.group(name.isEmpty() ? DEFAULT_GROUP : name), channels);
        }
    }

    /** TVBox/FongMi 的源列表：[{name,url}] 或 [{group,channels}]。 */
    private static void addUrlSources(Playlist playlist, JSONArray array, JSONObject parent) {
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                String url = array.optString(i, "");
                if (!url.isEmpty()) playlist.children.add(new LiveSource("线路" + (i + 1), url));
                continue;
            }
            String name = item.optString("name", "线路" + (i + 1));
            String url = item.optString("url", "");
            JSONArray channels = item.optJSONArray("channels");
            if (!url.isEmpty()) {
                LiveSource source = new LiveSource(name, url);
                source.epg = item.optString("epg", parent.optString("epg", ""));
                source.ua = item.optString("ua", parent.optString("ua", ""));
                playlist.children.add(source);
                if (playlist.epgUrl.isEmpty()) playlist.epgUrl = source.epg;
            } else if (channels != null) {
                parseChannelArray(playlist.group(name.isEmpty() ? DEFAULT_GROUP : name), channels);
            }
        }
    }

    /** 把 FongMi live 级别的 ua/origin/referer/header/catchup 应用到所属频道。 */
    private static void applyLiveDefaults(Playlist playlist, JSONObject live) {
        String ua = live.optString("ua", "");
        String origin = live.optString("origin", "");
        String referer = live.optString("referer", "");
        JSONObject header = live.optJSONObject("header");
        JSONObject catchup = catchupOf(live);
        for (Group group : playlist.groups) {
            for (Channel channel : group.channels) {
                if (channel.ua.isEmpty() && !ua.isEmpty()) channel.ua = ua;
                if (!origin.isEmpty() && !channel.headers.containsKey("Origin")) channel.headers.put("Origin", origin);
                if (!referer.isEmpty() && !channel.headers.containsKey("Referer")) channel.headers.put("Referer", referer);
                if (header != null) {
                    java.util.Iterator<String> keys = header.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        if (!channel.headers.containsKey(key)) channel.headers.put(key, header.optString(key, ""));
                    }
                }
                if (channel.catchupSource.isEmpty() && catchup != null) {
                    channel.catchupType = catchup.optString("type", "");
                    channel.catchupSource = catchup.optString("source", "");
                    channel.catchupReplace = catchup.optString("replace", "");
                }
            }
        }
    }

    private static void mergeGroups(Playlist target, Playlist source) {
        for (Group group : source.groups) {
            Group merged = target.group(group.name);
            for (Channel channel : group.channels) {
                Channel exist = merged.find(channel.name);
                if (exist == null) merged.channels.add(channel);
                else {
                    for (String url : channel.urls) exist.addUrl(url);
                    if (exist.ua.isEmpty()) exist.ua = channel.ua;
                    if (exist.logo.isEmpty()) exist.logo = channel.logo;
                    if (exist.danmaku.isEmpty()) exist.danmaku = channel.danmaku;
                }
            }
        }
    }

    private static void parseGroups(Playlist playlist, JSONArray array) {
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) continue;
            String name = object.optString("name", object.optString("group", DEFAULT_GROUP));
            JSONArray channels = object.optJSONArray("channels");
            if (channels == null) channels = object.optJSONArray("channel");
            if (channels == null) continue;
            parseChannelArray(playlist.group(name.isEmpty() ? DEFAULT_GROUP : name), channels);
        }
    }

    private static void parseChannelArray(Group group, JSONArray array) {
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) continue;
            String name = object.optString("name", "");
            if (name.isEmpty()) continue;
            Channel channel = group.findOrCreate(name);
            String tvgId = object.optString("tvgId", object.optString("tvg-id", object.optString("id", "")));
            if (!tvgId.isEmpty()) channel.tvgId = tvgId;
            String tvgName = object.optString("tvgName", object.optString("tvg-name", ""));
            if (!tvgName.isEmpty()) channel.tvgName = tvgName;
            String logo = object.optString("logo", "");
            if (!logo.isEmpty()) channel.logo = logo;
            String danmaku = object.optString("danmaku", object.optString("danmakuUrl", ""));
            if (!danmaku.isEmpty()) channel.danmaku = danmaku;
            String ua = object.optString("ua", "");
            if (!ua.isEmpty()) channel.ua = ua;
            String number = object.optString("number", "");
            if (!number.isEmpty()) channel.number = number;
            JSONObject header = object.optJSONObject("header");
            if (header != null) {
                java.util.Iterator<String> keys = header.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    channel.headers.put(key, header.optString(key, ""));
                }
            }
            JSONObject catchup = catchupOf(object);
            if (catchup != null) {
                channel.catchupType = catchup.optString("type", "");
                channel.catchupSource = catchup.optString("source", "");
                channel.catchupReplace = catchup.optString("replace", "");
            } else {
                String catchupSource = object.optString("catchup", "");
                if (!catchupSource.isEmpty() && catchupSource.contains("{")) {
                    channel.catchupSource = catchupSource;
                    channel.catchupType = "default";
                }
            }
            addUrls(channel, object);
        }
    }

    private static void addUrls(Channel channel, JSONObject object) {
        JSONArray array = object.optJSONArray("urls");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String url = array.optString(i, "").trim();
                if (!url.isEmpty()) channel.addUrl(url);
            }
            return;
        }
        String urls = object.optString("urls", object.optString("url", ""));
        if (urls.isEmpty()) return;
        for (String url : urls.split("#")) if (URL.matcher(url).find()) channel.addUrl(url.trim());
    }

    // ------------------------------------------------------------------ helpers

    private static String attr(String line, String key) {
        Matcher matcher = Pattern.compile(key + "=\"([^\"]*)\"").matcher(line);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String nameOf(String line) {
        int index = line.lastIndexOf(',');
        return index < 0 ? "" : line.substring(index + 1).trim();
    }

    private static Map<String, String> parseHeaderText(String text) {
        Map<String, String> headers = new HashMap<>();
        if (text == null) return headers;
        for (String item : text.split("&")) {
            int index = item.indexOf('=');
            if (index <= 0) continue;
            headers.put(item.substring(0, index).trim(), item.substring(index + 1).trim());
        }
        return headers;
    }

    private static class Pending {

        String ua;
        String referer;
        String cookie;
        final Map<String, String> headers = new HashMap<>();

        boolean find(String line) {
            return line.startsWith("ua") || line.startsWith("user-agent") || line.startsWith("referer")
                    || line.startsWith("origin") || line.startsWith("header") || line.startsWith("#EXTVLCOPT")
                    || line.startsWith("#EXTHTTP") || line.toUpperCase(java.util.Locale.US).startsWith("#KODIPROP");
        }

        void apply(String line) {
            try {
                String lower = line.toLowerCase();
                if (lower.startsWith("#extvlcopt:http-user-agent") || lower.startsWith("ua=") || lower.startsWith("ua:")) {
                    ua = after(line, "=");
                } else if (lower.startsWith("user-agent") || lower.startsWith("useragent")) {
                    ua = after(line, ":");
                    if (ua.isEmpty()) ua = after(line, "=");
                } else if (lower.startsWith("#extvlcopt:http-referrer") || lower.startsWith("#extvlcopt:http-referer") || lower.startsWith("referer")) {
                    referer = after(line, "=");
                    if (referer.isEmpty()) referer = after(line, ":");
                } else if (lower.startsWith("#extvlcopt:http-cookie")) {
                    cookie = after(line, "=");
                } else if (lower.startsWith("#exthttp:")) {
                    String json = line.substring(line.indexOf(':') + 1).trim();
                    JSONObject object = new JSONObject(json);
                    java.util.Iterator<String> keys = object.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        headers.put(key, object.optString(key, ""));
                    }
                } else if (lower.startsWith("#kodiprop:")) {
                    String value = line.substring(line.indexOf(':') + 1).trim();
                    int eq = value.indexOf('=');
                    if (eq > 0) {
                        String key = value.substring(0, eq).trim();
                        String headerValue = value.substring(eq + 1).trim();
                        if ("http-user-agent".equalsIgnoreCase(key)) ua = headerValue;
                        else if ("http-referrer".equalsIgnoreCase(key) || "http-referer".equalsIgnoreCase(key)) referer = headerValue;
                        else if ("http-cookie".equalsIgnoreCase(key)) cookie = headerValue;
                        else if (!key.isEmpty()) headers.put(key, headerValue);
                    }
                } else if (lower.startsWith("header")) {
                    String value = line.substring(line.indexOf(' ')).trim();
                    if (value.startsWith("{")) {
                        JSONObject object = new JSONObject(value);
                        java.util.Iterator<String> keys = object.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            headers.put(key, object.optString(key, ""));
                        }
                    } else {
                        headers.putAll(parseHeaderText(value));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        void parseHeaders(String text) {
            headers.putAll(parseHeaderText(text));
        }

        void reset() {
            ua = null;
            referer = null;
            cookie = null;
            headers.clear();
        }

        private static String after(String line, String separator) {
            int index = line.indexOf(separator);
            return index < 0 ? "" : line.substring(index + separator.length()).trim().replace("\"", "");
        }
    }
}

