package com.livetv.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class Channel {

    public String name = "";
    public String number = "";
    public String tvgId = "";
    public String tvgName = "";
    public String logo = "";
    public String danmaku = "";
    public String ua = "";
    public String group = "";
    public String catchupType = "";
    public String catchupSource = "";
    public String catchupReplace = "";
    public final List<String> urls = new ArrayList<>();
    public final Map<String, String> headers = new HashMap<>();
    // EPG 在子线程写入、UI 线程读取，采用写时复制避免遍历时 ConcurrentModificationException
    public final List<EpgProgram> programs = new CopyOnWriteArrayList<>();
    public int lineIndex = 0;

    public Channel() {
    }

    public Channel(String name) {
        this.name = name;
    }

    public String key() {
        return group + "|" + name;
    }

    public boolean isEmpty() {
        return name == null || name.trim().isEmpty();
    }

    public boolean hasCatchup() {
        return catchupSource != null && !catchupSource.trim().isEmpty();
    }

    public String url() {
        if (urls.isEmpty()) return "";
        int index = lineIndex;
        if (index < 0 || index >= urls.size()) index = 0;
        return urls.get(index);
    }

    public void addUrl(String url) {
        if (url == null) return;
        url = url.trim();
        if (url.isEmpty() || urls.contains(url)) return;
        urls.add(url);
    }

    public EpgProgram now() {
        long now = System.currentTimeMillis();
        for (EpgProgram program : programs) {
            if (program.startTime <= now && program.endTime > now) return program;
        }
        return null;
    }

    public EpgProgram next() {
        long now = System.currentTimeMillis();
        for (EpgProgram program : programs) {
            if (program.startTime > now) return program;
        }
        return null;
    }

    public int progress() {
        EpgProgram program = now();
        if (program == null) return 0;
        long total = program.endTime - program.startTime;
        if (total <= 0) return 0;
        long offset = System.currentTimeMillis() - program.startTime;
        return (int) Math.max(0, Math.min(100, offset * 100 / total));
    }

    public Channel copy(String groupName) {
        Channel channel = new Channel();
        channel.name = name;
        channel.number = number;
        channel.tvgId = tvgId;
        channel.tvgName = tvgName;
        channel.logo = logo;
        channel.danmaku = danmaku;
        channel.ua = ua;
        channel.group = groupName;
        channel.catchupType = catchupType;
        channel.catchupSource = catchupSource;
        channel.catchupReplace = catchupReplace;
        channel.urls.addAll(urls);
        channel.headers.putAll(headers);
        return channel;
    }

    @Override
    public String toString() {
        return name + " (" + urls.size() + ")";
    }
}

