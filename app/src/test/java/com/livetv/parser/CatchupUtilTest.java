package com.livetv.parser;

import com.livetv.model.Channel;
import com.livetv.model.EpgProgram;

import org.junit.Test;

import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * CatchupUtil 纯 JVM 单测：覆盖模板替换、时区与 catchup-replace 的 $ 转义。
 */
public class CatchupUtilTest {

    private static final long START = 1700000000000L; // 2023-11-14 22:13:20 UTC
    private static final long END = START + 3600_000L;

    private Channel channel(String type, String source, String replace) {
        Channel c = new Channel("测试频道");
        c.addUrl("http://example.com/live.m3u8");
        c.catchupType = type;
        c.catchupSource = source;
        c.catchupReplace = replace;
        return c;
    }

    @Test
    public void defaultMode_returnsSourceWithTemplateResolved() {
        TimeZone before = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            Channel c = channel("default", "http://x/{date}/{utc}.ts", "");
            String result = CatchupUtil.build(c, new EpgProgram("p", START, END));
            assertEquals("http://x/20231114/20231114221320.ts", result);
        } finally {
            TimeZone.setDefault(before);
        }
    }

    @Test
    public void dollarPrefixedToken_isResolved() {
        TimeZone before = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            Channel c = channel("default", "http://x/${utc}.ts", "");
            String result = CatchupUtil.build(c, new EpgProgram("p", START, END));
            assertEquals("http://x/20231114221320.ts", result);
        } finally {
            TimeZone.setDefault(before);
        }
    }

    @Test
    public void timestampToken_isUnixSeconds() {
        Channel c = channel("default", "http://x/?t={timestamp}", "");
        String result = CatchupUtil.build(c, new EpgProgram("p", START, END));
        assertEquals("http://x/?t=" + (START / 1000), result);
    }

    @Test
    public void appendMode_appendsSourceToUrl() {
        Channel c = channel("append", "?start={timestamp}", "");
        String result = CatchupUtil.build(c, new EpgProgram("p", START, END));
        assertTrue(result.startsWith("http://example.com/live.m3u8?start="));
    }

    @Test
    public void appendMode_usesAmpersandWhenUrlHasQuery() {
        Channel c = channel("append", "?start=1", "");
        c.urls.clear();
        c.addUrl("http://example.com/live.m3u8?token=abc");
        String result = CatchupUtil.build(c, new EpgProgram("p", START, END));
        assertEquals("http://example.com/live.m3u8?token=abc&start=1", result);
    }

    @Test
    public void replaceWithDollar_doesNotThrowAndIsLiteral() {
        // 替换串含 $ 时必须按字面处理，不能被当作正则分组引用
        Channel c = channel("append", "?start=1", "live\\.m3u8,new$file.m3u8");
        String result = CatchupUtil.build(c, new EpgProgram("p", START, END));
        assertTrue(result.contains("new$file.m3u8"));
    }

    @Test
    public void noCatchup_returnsEmpty() {
        Channel c = new Channel("no");
        assertEquals("", CatchupUtil.build(c, new EpgProgram("p", START, END)));
    }

    @Test
    public void endTokens_resolveToEndTime() {
        TimeZone before = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            Channel c = channel("default", "http://x/{enddate}/{utcend}.ts", "");
            String result = CatchupUtil.build(c, new EpgProgram("p", START, END));
            assertEquals("http://x/20231114/20231114231320.ts", result);
        } finally {
            TimeZone.setDefault(before);
        }
    }
}
