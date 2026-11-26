package com.livetv;

import android.content.Context;
import android.content.SharedPreferences;

import com.livetv.model.Channel;
import com.livetv.model.LiveSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Store {

    private static final String NAME = "livetv";
    private static final String KEY_SOURCES = "sources";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_FAV = "fav";
    private static final String KEY_ASPECT = "aspect";
    private static final String KEY_BUFFER = "buffer";
    private static final String KEY_CROSS = "cross";
    private static final String KEY_AUTOSTART = "autostart";
    private static final String KEY_TIMESHIFT = "timeshift";
    private static final String KEY_TIMESHIFT_WINDOW = "timeshift_window";
    private static final String KEY_DECODE = "decode_mode";
    private static final String KEY_PLAYER_ENGINE = "player_engine";
    private static final String KEY_HIDDEN_GROUPS = "hidden_groups";
    private static final String KEY_SHOW_HIDDEN = "show_hidden";
    private static final String KEY_LAN = "lan_server";
    private static final String KEY_DANMAKU = "danmaku";
    private static final String KEY_RELOAD = "reload_token";

    private static SharedPreferences preferences;

    private Store() {
    }

    public static void init(Context context) {
        if (preferences == null) preferences = context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    private static SharedPreferences sp(Context context) {
        init(context);
        return preferences;
    }

    public static List<LiveSource> sources(Context context) {
        List<LiveSource> items = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(sp(context).getString(KEY_SOURCES, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object != null) items.add(LiveSource.from(object));
            }
        } catch (Exception ignored) {
        }
        return items;
    }

    public static void saveSources(Context context, List<LiveSource> items) {
        JSONArray array = new JSONArray();
        for (LiveSource source : items) array.put(source.toJson());
        sp(context).edit().putString(KEY_SOURCES, array.toString()).apply();
    }

    public static int activeIndex(Context context) {
        List<LiveSource> items = sources(context);
        if (items.isEmpty()) return -1;
        int index = sp(context).getInt(KEY_ACTIVE, 0);
        if (index < 0 || index >= items.size()) index = 0;
        return index;
    }

    public static void setActive(Context context, int index) {
        sp(context).edit().putInt(KEY_ACTIVE, index).apply();
    }

    public static LiveSource activeSource(Context context) {
        List<LiveSource> items = sources(context);
        int index = activeIndex(context);
        return index < 0 || index >= items.size() ? null : items.get(index);
    }

    public static int add(Context context, LiveSource source) {
        List<LiveSource> items = sources(context);
        items.add(source);
        saveSources(context, items);
        if (items.size() == 1) setActive(context, 0);
        return items.size() - 1;
    }

    public static void update(Context context, int index, LiveSource source) {
        List<LiveSource> items = sources(context);
        if (index < 0 || index >= items.size()) return;
        items.set(index, source);
        saveSources(context, items);
    }

    public static void remove(Context context, int index) {
        List<LiveSource> items = sources(context);
        if (index < 0 || index >= items.size()) return;
        // 读取删除前的真实活动位置；不能调用 activeIndex()，它会把越界值钳到 0，
        // 导致无法判断删除项与活动项的相对位置，进而串台。
        int active = sp(context).getInt(KEY_ACTIVE, 0);
        items.remove(index);
        saveSources(context, items);
        if (items.isEmpty()) {
            setActive(context, -1);
        } else if (index < active) {
            // 删除项在活动项之前，活动项下标前移一位
            setActive(context, active - 1);
        } else if (index == active) {
            // 删除的正是活动项，顺延到同一位置的下一项（若已是末项则回到最后一项）
            setActive(context, Math.min(active, items.size() - 1));
        }
        // index > active 时活动项下标不变，无需写入
        bumpReload(context);
    }

    public static Set<String> favorites(Context context) {
        Set<String> stored = sp(context).getStringSet(KEY_FAV, new HashSet<String>());
        return new HashSet<>(stored);
    }

    public static boolean isFavorite(Context context, Channel channel) {
        return favorites(context).contains(channel.key());
    }

    public static void toggleFavorite(Context context, Channel channel) {
        Set<String> favorites = favorites(context);
        if (!favorites.remove(channel.key())) favorites.add(channel.key());
        sp(context).edit().putStringSet(KEY_FAV, favorites).apply();
    }

    private static String lastKey(LiveSource source) {
        return "last_" + (source == null ? "none" : source.key());
    }

    public static String lastChannel(Context context, LiveSource source) {
        return sp(context).getString(lastKey(source), "");
    }

    public static void saveLastChannel(Context context, LiveSource source, Channel channel) {
        if (source == null || channel == null) return;
        sp(context).edit().putString(lastKey(source), channel.group + "|" + channel.name).apply();
    }

    public static int aspect(Context context) {
        return sp(context).getInt(KEY_ASPECT, 0);
    }

    public static void setAspect(Context context, int value) {
        sp(context).edit().putInt(KEY_ASPECT, value).apply();
    }

    public static int buffer(Context context) {
        return sp(context).getInt(KEY_BUFFER, 8);
    }

    public static void setBuffer(Context context, int value) {
        sp(context).edit().putInt(KEY_BUFFER, value).apply();
    }

    public static boolean crossGroup(Context context) {
        return sp(context).getBoolean(KEY_CROSS, true);
    }

    public static void setCrossGroup(Context context, boolean value) {
        sp(context).edit().putBoolean(KEY_CROSS, value).apply();
    }

    public static boolean autoStart(Context context) {
        return sp(context).getBoolean(KEY_AUTOSTART, false);
    }

    public static void setAutoStart(Context context, boolean value) {
        sp(context).edit().putBoolean(KEY_AUTOSTART, value).apply();
    }

    /** 直播时移：把 HLS 直播缓存到本地，支持暂停、回拖。 */
    public static boolean timeshift(Context context) {
        return sp(context).getBoolean(KEY_TIMESHIFT, true);
    }

    public static void setTimeshift(Context context, boolean value) {
        sp(context).edit().putBoolean(KEY_TIMESHIFT, value).apply();
    }

    /** 时移窗口，单位分钟。 */
    public static int timeshiftMinutes(Context context) {
        return sp(context).getInt(KEY_TIMESHIFT_WINDOW, 10);
    }

    public static void setTimeshiftMinutes(Context context, int minutes) {
        sp(context).edit().putInt(KEY_TIMESHIFT_WINDOW, minutes).apply();
    }

    public static long timeshiftWindow(Context context) {
        return Math.max(1, timeshiftMinutes(context)) * 60000L;
    }

    /** 解码方式：0 自动（硬解优先），1 优先软解。 */
    public static int decodeMode(Context context) {
        return sp(context).getInt(KEY_DECODE, 0);
    }

    public static void setDecodeMode(Context context, int mode) {
        sp(context).edit().putInt(KEY_DECODE, mode).apply();
    }

    /** 播放内核：0 ExoPlayer（默认），1 IJKPlayer，2 mpv。 */
    public static int playerEngine(Context context) {
        return sp(context).getInt(KEY_PLAYER_ENGINE, 0);
    }

    public static void setPlayerEngine(Context context, int engine) {
        sp(context).edit().putInt(KEY_PLAYER_ENGINE, engine).apply();
    }

    public static Set<String> hiddenGroups(Context context) {
        return new HashSet<>(sp(context).getStringSet(KEY_HIDDEN_GROUPS, new HashSet<String>()));
    }

    public static void setGroupHidden(Context context, String group, boolean hidden) {
        Set<String> groups = hiddenGroups(context);
        if (hidden) groups.add(group);
        else groups.remove(group);
        sp(context).edit().putStringSet(KEY_HIDDEN_GROUPS, groups).apply();
    }

    public static boolean showHiddenGroups(Context context) {
        return sp(context).getBoolean(KEY_SHOW_HIDDEN, false);
    }

    public static boolean danmaku(Context context) {
        return sp(context).getBoolean(KEY_DANMAKU, true);
    }

    public static void setDanmaku(Context context, boolean value) {
        sp(context).edit().putBoolean(KEY_DANMAKU, value).apply();
    }

    public static boolean lanServer(Context context) {
        return sp(context).getBoolean(KEY_LAN, false);
    }

    public static void setLanServer(Context context, boolean value) {
        sp(context).edit().putBoolean(KEY_LAN, value).apply();
    }

    public static void setShowHiddenGroups(Context context, boolean value) {
        sp(context).edit().putBoolean(KEY_SHOW_HIDDEN, value).apply();
    }

    public static void bumpReload(Context context) {
        sp(context).edit().putInt(KEY_RELOAD, reloadToken(context) + 1).apply();
    }

    public static int reloadToken(Context context) {
        return sp(context).getInt(KEY_RELOAD, 0);
    }
}


