package com.livetv.parser;

import com.livetv.model.Channel;
import com.livetv.model.EpgProgram;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CatchupUtil {

    private static final Pattern TOKEN = Pattern.compile("(\\$?\\{[^}]*\\})");
    private static final Pattern TAG = Pattern.compile("\\{([^}]+)\\}");

    private CatchupUtil() {
    }

    public static String build(Channel channel, EpgProgram program) {
        if (channel == null || program == null || !channel.hasCatchup()) return "";
        String source = channel.catchupSource;
        Matcher matcher = TOKEN.matcher(source);
        while (matcher.find()) {
            String token = matcher.group(1);
            String value = value(token, program);
            source = source.replace(token, value);
            matcher = TOKEN.matcher(source);
        }
        if ("default".equalsIgnoreCase(channel.catchupType)) return source;
        return append(channel, source);
    }

    private static String append(Channel channel, String source) {
        String url = channel.url();
        if (channel.catchupReplace != null && channel.catchupReplace.contains(",")) {
            String[] splits = channel.catchupReplace.split(",", 2);
            try {
                // 替换串里的 $ 必须转义，否则会被当作正则分组引用导致替换静默失效
                url = url.replaceAll(splits[0], Matcher.quoteReplacement(splits[1]));
            } catch (Exception ignored) {
            }
        }
        if (url.contains("?") && source.startsWith("?")) source = "&" + source.substring(1);
        return url + source;
    }

    private static String value(String token, EpgProgram program) {
        Matcher matcher = TAG.matcher(token);
        if (!matcher.find()) return "";
        String tag = matcher.group(1);
        int paren = tag.indexOf(')');
        if (tag.startsWith("(b") && paren >= 0) return format(program.startTime, tag.substring(paren + 1));
        if (tag.startsWith("(e") && paren >= 0) return format(program.endTime, tag.substring(paren + 1));
        if (tag.equals("utc")) return format(program.startTime, "yyyyMMddHHmmss|UTC");
        if (tag.equals("utcend")) return format(program.endTime, "yyyyMMddHHmmss|UTC");
        if (tag.startsWith("utcend:")) return String.valueOf(program.endTime / 1000);
        if (tag.startsWith("utc:")) return String.valueOf(program.startTime / 1000);
        if (tag.equals("timestamp")) return String.valueOf(program.startTime / 1000);
        if (tag.equals("date")) return format(program.startTime, "yyyyMMdd");
        if (tag.equals("enddate")) return format(program.endTime, "yyyyMMdd");
        return "";
    }

    private static String format(long millis, String pattern) {
        boolean utc = pattern.endsWith("|UTC");
        if (utc) pattern = pattern.substring(0, pattern.length() - 4);
        try {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
            if (utc) format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.format(new Date(millis));
        } catch (Exception e) {
            return "";
        }
    }
}
