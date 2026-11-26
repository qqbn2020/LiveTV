package com.livetv.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Fmt {

    private static final Pattern HOST = Pattern.compile("^(?:https?|rtmp|rtsp)://([^/]+)");

    private Fmt() {
    }

    public static String time(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    public static String clock(long millis) {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(millis));
    }

    public static String date(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(millis));
    }

    public static String range(long start, long end) {
        if (start <= 0) return "";
        return time(start) + "-" + time(end);
    }

    public static String number(int number) {
        return String.format(Locale.US, "%03d", number);
    }

    public static String duration(long millis) {
        if (millis < 0) millis = 0;
        long total = millis / 1000;
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        if (hours > 0) return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    public static String host(String url) {
        if (url == null) return "";
        Matcher matcher = HOST.matcher(url);
        return matcher.find() ? matcher.group(1) : url;
    }

    public static long dayStart(long millis) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
        calendar.set(java.util.Calendar.MINUTE, 0);
        calendar.set(java.util.Calendar.SECOND, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    public static long dayAdd(long millis, int days) {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        calendar.add(java.util.Calendar.DAY_OF_YEAR, days);
        return calendar.getTimeInMillis();
    }
}
