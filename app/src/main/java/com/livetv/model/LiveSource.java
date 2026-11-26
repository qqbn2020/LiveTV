package com.livetv.model;

import org.json.JSONObject;

public class LiveSource {

    public String name;
    public String url;
    public String epg;
    public String ua;

    public LiveSource() {
    }

    public LiveSource(String name, String url) {
        this.name = name;
        this.url = url;
        this.epg = "";
        this.ua = "";
    }

    public static LiveSource from(JSONObject object) {
        LiveSource source = new LiveSource();
        source.name = object.optString("name", "");
        source.url = object.optString("url", "");
        source.epg = object.optString("epg", "");
        source.ua = object.optString("ua", "");
        return source;
    }

    public JSONObject toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("name", name == null ? "" : name);
            object.put("url", url == null ? "" : url);
            object.put("epg", epg == null ? "" : epg);
            object.put("ua", ua == null ? "" : ua);
        } catch (Exception ignored) {
        }
        return object;
    }

    public String displayName() {
        return name == null || name.trim().isEmpty() ? url : name;
    }

    public String key() {
        return String.valueOf(url == null ? 0 : url.hashCode());
    }

    public LiveSource copy() {
        LiveSource source = new LiveSource();
        source.name = name;
        source.url = url;
        source.epg = epg;
        source.ua = ua;
        return source;
    }
}
