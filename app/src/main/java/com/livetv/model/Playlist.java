package com.livetv.model;

import java.util.ArrayList;
import java.util.List;

public class Playlist {

    public final List<Group> groups = new ArrayList<>();
    public final List<LiveSource> children = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public String epgUrl = "";

    public Group group(String name) {
        for (Group group : groups) {
            if (group.name.equals(name)) return group;
        }
        Group created = new Group(name);
        groups.add(created);
        return created;
    }

    public boolean isEmpty() {
        for (Group group : groups) if (!group.isEmpty()) return false;
        return true;
    }

    public int channelCount() {
        int count = 0;
        for (Group group : groups) count += group.channels.size();
        return count;
    }

    public List<Channel> allChannels() {
        List<Channel> channels = new ArrayList<>();
        for (Group group : groups) channels.addAll(group.channels);
        return channels;
    }
}
