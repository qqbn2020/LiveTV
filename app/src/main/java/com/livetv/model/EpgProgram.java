package com.livetv.model;

public class EpgProgram {

    public String title = "";
    public String desc = "";
    public long startTime;
    public long endTime;

    public EpgProgram() {
    }

    public EpgProgram(String title, long startTime, long endTime) {
        this.title = title;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public boolean isPlaying() {
        long now = System.currentTimeMillis();
        return startTime <= now && endTime > now;
    }

    public EpgProgram copy() {
        EpgProgram copy = new EpgProgram(title, startTime, endTime);
        copy.desc = desc;
        return copy;
    }

    public boolean isPast() {
        return endTime <= System.currentTimeMillis();
    }

    public boolean isFuture() {
        return startTime > System.currentTimeMillis();
    }
}
