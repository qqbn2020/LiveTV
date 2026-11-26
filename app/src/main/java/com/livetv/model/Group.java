package com.livetv.model;

import java.util.ArrayList;
import java.util.List;

public class Group {

    public String name;
    public final List<Channel> channels = new ArrayList<>();

    public Group(String name) {
        this.name = name;
    }

    public Channel find(String channelName) {
        for (int i = channels.size() - 1; i >= 0; i--) {
            Channel channel = channels.get(i);
            if (channel.name.equals(channelName)) return channel;
        }
        return null;
    }

    public Channel findOrCreate(String channelName) {
        Channel channel = find(channelName);
        if (channel != null) return channel;
        Channel created = new Channel(channelName);
        created.group = name;
        channels.add(created);
        return created;
    }

    public boolean isEmpty() {
        return channels.isEmpty();
    }
}
