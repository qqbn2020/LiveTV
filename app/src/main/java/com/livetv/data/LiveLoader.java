package com.livetv.data;

import android.content.Context;

import com.livetv.model.Channel;
import com.livetv.model.Group;
import com.livetv.model.LiveSource;
import com.livetv.model.Playlist;
import com.livetv.net.Http;
import com.livetv.parser.PlaylistParser;

public final class LiveLoader {

    private static final int MAX_DEPTH = 2;

    private LiveLoader() {
    }

    public static Playlist load(Context context, LiveSource source) throws Exception {
        return load(context, source, 0);
    }

    private static Playlist load(Context context, LiveSource source, int depth) throws Exception {
        if (source == null || source.url == null || source.url.trim().isEmpty()) throw new Exception("empty source");
        String text = Http.string(context, source.url.trim(), source.ua, null);
        Playlist playlist = PlaylistParser.parse(text, source.url);
        if (source.epg != null && !source.epg.trim().isEmpty()) playlist.epgUrl = source.epg.trim();
        if (playlist.children.isEmpty() || depth >= MAX_DEPTH) return playlist;

        for (LiveSource child : playlist.children) {
            try {
                Playlist parsed = load(context, child, depth + 1);
                merge(playlist, parsed);
                for (String warning : parsed.warnings) {
                    if (!playlist.warnings.contains(warning)) playlist.warnings.add(warning);
                }
                if (playlist.epgUrl.isEmpty()) playlist.epgUrl = parsed.epgUrl;
            } catch (Exception e) {
                playlist.warnings.add("子源加载失败：" + child.name);
            }
        }
        playlist.children.clear();
        return playlist;
    }

    public static void merge(Playlist target, Playlist source) {
        for (Group group : source.groups) {
            Group merged = target.group(group.name);
            for (Channel channel : group.channels) {
                Channel exist = merged.find(channel.name);
                if (exist == null) {
                    merged.channels.add(channel);
                } else {
                    for (String url : channel.urls) exist.addUrl(url);
                    if (exist.tvgId.isEmpty()) exist.tvgId = channel.tvgId;
                    if (exist.tvgName.isEmpty()) exist.tvgName = channel.tvgName;
                    if (exist.ua.isEmpty()) exist.ua = channel.ua;
                    if (exist.logo.isEmpty()) exist.logo = channel.logo;
                    if (exist.danmaku.isEmpty()) exist.danmaku = channel.danmaku;
                    if (exist.catchupSource.isEmpty()) {
                        exist.catchupSource = channel.catchupSource;
                        exist.catchupType = channel.catchupType;
                        exist.catchupReplace = channel.catchupReplace;
                    }
                }
            }
        }
    }
}

