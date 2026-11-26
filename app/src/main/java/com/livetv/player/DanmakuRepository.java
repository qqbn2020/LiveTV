package com.livetv.player;

import android.content.Context;

import com.livetv.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 弹幕数据加载：支持 B 站风格 XML（&lt;d p="秒,..."&gt;文本&lt;/d&gt;）以及
 * JSON（<code>[{"time":秒,"text":"..."}]</code> / B 站 <code>{"elems":[{"progress":毫秒,"content":"..."}]}</code>）。
 */
public final class DanmakuRepository {

    private static final Pattern XML_ITEM = Pattern.compile("<d\\s+p=\"([^\"]*)\"[^>]*>(.*?)</d>", Pattern.DOTALL);

    private DanmakuRepository() {
    }

    public static List<DanmakuItem> load(Context context, String url, String ua, Map<String, String> headers) throws Exception {
        String text = Http.string(context, url, ua, headers);
        List<DanmakuItem> items = new ArrayList<>();
        if (text.trim().startsWith("<")) xml(text, items);
        else json(text, items);
        Collections.sort(items, new Comparator<DanmakuItem>() {
            @Override
            public int compare(DanmakuItem a, DanmakuItem b) {
                return Long.compare(a.timeMs, b.timeMs);
            }
        });
        return items;
    }

    private static void xml(String text, List<DanmakuItem> items) {
        Matcher matcher = XML_ITEM.matcher(text);
        while (matcher.find()) {
            String p = matcher.group(1);
            String content = unescape(matcher.group(2).replaceAll("<[^>]+>", ""));
            double seconds = 0;
            try {
                seconds = Double.parseDouble(p.split(",")[0]);
            } catch (Exception ignored) {
            }
            if (!content.trim().isEmpty()) items.add(new DanmakuItem((long) (seconds * 1000), content.trim()));
        }
    }

    private static void json(String text, List<DanmakuItem> items) {
        try {
            String trimmed = text.trim();
            JSONArray array = trimmed.startsWith("[") ? new JSONArray(trimmed) : null;
            if (array != null) {
                for (int i = 0; i < array.length(); i++) addJson(items, array.optJSONObject(i));
                return;
            }
            JSONObject root = new JSONObject(trimmed);
            JSONArray elems = root.optJSONArray("elems");
            if (elems != null) {
                for (int i = 0; i < elems.length(); i++) addJson(items, elems.optJSONObject(i));
                return;
            }
            JSONArray list = root.optJSONArray("danmaku");
            if (list != null) for (int i = 0; i < list.length(); i++) addJson(items, list.optJSONObject(i));
        } catch (Exception ignored) {
        }
    }

    private static void addJson(List<DanmakuItem> items, JSONObject object) {
        if (object == null) return;
        long time = object.has("progress") ? object.optLong("progress") : (long) (object.optDouble("time", 0) * 1000);
        String text = object.optString("content", object.optString("text", object.optString("m", "")));
        if (!text.trim().isEmpty()) items.add(new DanmakuItem(Math.max(0, time), text.trim()));
    }

    private static String unescape(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
    }
}



