package com.livetv.parser;

import android.util.Xml;
import android.util.Log;

import com.livetv.model.EpgProgram;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EpgParser {

    private static final Pattern DIGITS = Pattern.compile("(\\d{10,14})");
    private static final Pattern OFFSET = Pattern.compile("(Z|UTC|GMT|[+-]\\d{2}:?\\d{2})");

    private EpgParser() {
    }

    public static class Result {
        public final Map<String, List<EpgProgram>> byChannel = new HashMap<>();
        public final Map<String, List<String>> names = new HashMap<>();
    }

    public static Result parse(String xml) throws Exception {
        Result result = new Result();
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(new StringReader(xml));

        String channelId = null;
        boolean inChannel = false;
        boolean inProgramme = false;
        String programmeChannel = null;
        long start = 0;
        long stop = 0;
        String title = "";
        String desc = "";

        int event;
        while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                if ("channel".equalsIgnoreCase(tag)) {
                    channelId = attr(parser, "id");
                    inChannel = true;
                    if (!channelId.isEmpty()) result.names.put(channelId, new ArrayList<String>());
                } else if ("display-name".equalsIgnoreCase(tag) && inChannel && channelId != null) {
                    String name = safeText(parser);
                    if (!name.isEmpty()) result.names.get(channelId).add(name);
                } else if ("programme".equalsIgnoreCase(tag)) {
                    programmeChannel = attr(parser, "channel");
                    start = parseTime(attr(parser, "start"));
                    stop = parseTime(attr(parser, "stop"));
                    title = "";
                    desc = "";
                    inProgramme = true;
                } else if ("title".equalsIgnoreCase(tag) && inProgramme) {
                    title = safeText(parser);
                } else if ("desc".equalsIgnoreCase(tag) && inProgramme) {
                    desc = safeText(parser);
                }
            } else if (event == XmlPullParser.END_TAG) {
                String tag = parser.getName();
                if ("channel".equalsIgnoreCase(tag)) {
                    inChannel = false;
                    channelId = null;
                } else if ("programme".equalsIgnoreCase(tag)) {
                    if (inProgramme && programmeChannel != null && !programmeChannel.isEmpty() && start > 0 && !title.isEmpty()) {
                        EpgProgram program = new EpgProgram(title, start, stop);
                        program.desc = desc;
                        List<EpgProgram> list = result.byChannel.get(programmeChannel);
                        if (list == null) {
                            list = new ArrayList<>();
                            result.byChannel.put(programmeChannel, list);
                        }
                        list.add(program);
                    }
                    inProgramme = false;
                    programmeChannel = null;
                }
            }
        }

        for (List<EpgProgram> list : result.byChannel.values()) {
            Collections.sort(list, new Comparator<EpgProgram>() {
                @Override
                public int compare(EpgProgram left, EpgProgram right) {
                    return Long.compare(left.startTime, right.startTime);
                }
            });
            fixEndTimes(list);
        }
        Log.i("LiveTV", "EPG parse channels=" + result.names.size() + " programmeChannels=" + result.byChannel.size());
        return result;
    }

    private static void fixEndTimes(List<EpgProgram> list) {
        for (int i = 0; i < list.size(); i++) {
            EpgProgram program = list.get(i);
            long next = i + 1 < list.size() ? list.get(i + 1).startTime : 0;
            if (program.endTime <= program.startTime) {
                program.endTime = next > program.startTime ? next : program.startTime + 30 * 60 * 1000L;
            }
        }
    }

    private static String attr(XmlPullParser parser, String name) {
        String value = parser.getAttributeValue(null, name);
        return value == null ? "" : value.trim();
    }

    private static String safeText(XmlPullParser parser) {
        // 不用 nextText()：标题/描述里可能含 <sub> 等标记，nextText() 遇到子元素会抛异常并丢弃整档节目
        StringBuilder text = new StringBuilder();
        try {
            int depth = 1;
            while (depth > 0) {
                int event = parser.next();
                if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) {
                    String value = parser.getText();
                    if (value != null) text.append(value);
                } else if (event == XmlPullParser.START_TAG) {
                    depth++;
                } else if (event == XmlPullParser.END_TAG) {
                    depth--;
                } else if (event == XmlPullParser.END_DOCUMENT) {
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        return text.toString().trim();
    }

    private static long parseTime(String text) {
        if (text == null) return 0;
        text = text.trim();
        if (text.isEmpty()) return 0;
        Matcher digits = DIGITS.matcher(text);
        if (!digits.find()) return 0;
        String value = digits.group(1);
        try {
            if (value.length() == 10) return Long.parseLong(value) * 1000L;
            if (value.length() != 14) return 0;
            Matcher offset = OFFSET.matcher(text.substring(digits.end()));
            if (offset.find()) {
                String zone = offset.group(1);
                if ("Z".equals(zone) || "UTC".equals(zone) || "GMT".equals(zone)) zone = "+0000";
                zone = zone.replace(":", "");
                SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date date = format.parse(value + " " + zone);
                return date == null ? 0 : date.getTime();
            }
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            Date date = format.parse(value);
            return date == null ? 0 : date.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    public static String normalizeName(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("-", "");
    }
}
