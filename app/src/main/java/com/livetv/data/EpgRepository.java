package com.livetv.data;

import android.content.Context;
import android.util.Log;

import com.livetv.model.Channel;
import com.livetv.model.EpgProgram;
import com.livetv.net.Http;
import com.livetv.parser.EpgParser;
import com.livetv.util.Fmt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EpgRepository {

    private static final long TTL = 60 * 60 * 1000L;
    private static final int MAX_CACHE_ENTRIES = 64;
    private static final Map<String, Cache> CACHE = new LinkedHashMap<String, Cache>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cache> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    private EpgRepository() {
    }

    private static class Cache {
        final long time = System.currentTimeMillis();
        final EpgParser.Result result;

        Cache(EpgParser.Result result) {
            this.result = result;
        }
    }

    public static boolean load(Context context, String epgUrl, List<Channel> channels) {
        if (epgUrl == null || epgUrl.trim().isEmpty() || channels == null || channels.isEmpty()) return false;
        EpgParser.Result merged = new EpgParser.Result();
        int success = 0;
        for (int offset = -1; offset <= 1; offset++) {
            String url = urlFor(epgUrl.trim(), offset);
            try {
                EpgParser.Result result = fetch(context, url);
                merge(merged, result);
                success++;
            } catch (Exception e) {
                Log.w("LiveTV", "EPG fetch failed " + url, e);
            }
        }
        Log.i("LiveTV", "EPG load success=" + success + " channels=" + merged.byChannel.size() + " names=" + merged.names.size());
        if (success == 0) return false;
        bind(channels, merged);
        int bound = 0;
        for (Channel channel : channels) if (!channel.programs.isEmpty()) bound++;
        Log.i("LiveTV", "EPG bound channels=" + bound + "/" + channels.size());
        return true;
    }

    private static EpgParser.Result fetch(Context context, String url) throws Exception {
        // 整段加锁：避免并发刷新时重复下载同一 URL，也避免缓存对象被并发替换
        synchronized (CACHE) {
            Cache cache = CACHE.get(url);
            if (cache != null && System.currentTimeMillis() - cache.time < TTL) return cache.result;
            String text = Http.string(context, url, null, null);
            EpgParser.Result result = EpgParser.parse(text);
            if (result.byChannel.isEmpty() && result.names.isEmpty()) throw new IOException("empty epg: " + url);
            CACHE.put(url, new Cache(result));
            return result;
        }
    }

    private static String urlFor(String url, int offset) {
        long day = Fmt.dayAdd(Fmt.dayStart(System.currentTimeMillis()), offset);
        return url.replace("{date}", Fmt.date(day));
    }

    private static void merge(EpgParser.Result target, EpgParser.Result source) {
        for (Map.Entry<String, List<EpgProgram>> entry : source.byChannel.entrySet()) {
            List<EpgProgram> list = target.byChannel.get(entry.getKey());
            if (list == null) {
                list = new ArrayList<>();
                target.byChannel.put(entry.getKey(), list);
            }
            list.addAll(entry.getValue());
        }
        for (Map.Entry<String, List<String>> entry : source.names.entrySet()) {
            List<String> list = target.names.get(entry.getKey());
            if (list == null) {
                list = new ArrayList<>();
                target.names.put(entry.getKey(), list);
            }
            for (String name : entry.getValue()) if (!list.contains(name)) list.add(name);
        }
    }

    private static void bind(List<Channel> channels, EpgParser.Result result) {
        // 复制后再去重，避免 dedupe() 修改 CACHE 中的对象造成缓存污染
        EpgParser.Result safe = new EpgParser.Result();
        for (Map.Entry<String, List<EpgProgram>> entry : result.byChannel.entrySet()) {
            List<EpgProgram> copy = new ArrayList<>();
            for (EpgProgram program : entry.getValue()) copy.add(program.copy());
            dedupe(copy);
            safe.byChannel.put(entry.getKey(), copy);
        }
        safe.names.putAll(result.names);
        for (Channel channel : channels) {
            List<EpgProgram> programs = find(channel, safe, channels.size());
            channel.programs.clear();
            if (programs != null) channel.programs.addAll(programs);
        }
    }

    private static List<EpgProgram> find(Channel channel, EpgParser.Result result, int channelCount) {
        if (channel.tvgId != null && !channel.tvgId.isEmpty()) {
            List<EpgProgram> list = result.byChannel.get(channel.tvgId);
            if (list != null && !list.isEmpty()) return list;
        }
        List<EpgProgram> list = byName(result, channel.tvgName);
        if (list != null) return list;
        list = byName(result, channel.name);
        if (list != null) return list;
        if (channelCount == 1 && result.byChannel.size() == 1 && result.names.size() <= 1) {
            List<EpgProgram> only = result.byChannel.values().iterator().next();
            if (!only.isEmpty()) return only;
        }
        return null;
    }

    private static List<EpgProgram> byName(EpgParser.Result result, String name) {
        if (name == null || name.trim().isEmpty()) return null;
        String normalized = EpgParser.normalizeName(name);
        for (Map.Entry<String, List<String>> entry : result.names.entrySet()) {
            for (String display : entry.getValue()) {
                if (EpgParser.normalizeName(display).equals(normalized)) {
                    List<EpgProgram> list = result.byChannel.get(entry.getKey());
                    if (list != null && !list.isEmpty()) return list;
                }
            }
        }
        List<EpgProgram> direct = result.byChannel.get(name);
        if (direct != null && !direct.isEmpty()) return direct;
        return null;
    }

    private static void dedupe(List<EpgProgram> list) {
        Collections.sort(list, new Comparator<EpgProgram>() {
            @Override
            public int compare(EpgProgram left, EpgProgram right) {
                return Long.compare(left.startTime, right.startTime);
            }
        });
        List<EpgProgram> unique = new ArrayList<>();
        for (EpgProgram program : list) {
            EpgProgram last = unique.isEmpty() ? null : unique.get(unique.size() - 1);
            if (last != null && program.startTime == last.startTime && program.title.equals(last.title)) {
                if (program.endTime > last.endTime) last.endTime = program.endTime;
                continue;
            }
            unique.add(program);
        }
        list.clear();
        list.addAll(unique);
    }

    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }
}
