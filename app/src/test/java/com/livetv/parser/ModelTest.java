package com.livetv.parser;

import com.livetv.model.Channel;
import com.livetv.model.Group;
import com.livetv.model.Playlist;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** 纯 JVM 单测：分组/频道模型与去重、复制语义。 */
public class ModelTest {

    @Test
    public void group_reusesSameNamedGroup() {
        Playlist playlist = new Playlist();
        Group a = playlist.group("央视");
        Group b = playlist.group("央视");
        assertSame(a, b);
        assertEquals(1, playlist.groups.size());
    }

    @Test
    public void findOrCreate_returnsSameChannelForDuplicateName() {
        Group group = new Group("央视");
        Channel c1 = group.findOrCreate("CCTV1");
        Channel c2 = group.findOrCreate("CCTV1");
        assertSame(c1, c2);
        assertEquals(1, group.channels.size());
    }

    @Test
    public void findOrCreate_setsGroupName() {
        Group group = new Group("央视");
        Channel c = group.findOrCreate("CCTV1");
        assertEquals("央视", c.group);
    }

    @Test
    public void addUrl_deduplicatesAndTrims() {
        Channel c = new Channel("x");
        c.addUrl("  http://a  ");
        c.addUrl("http://a");
        c.addUrl("");
        assertEquals(1, c.urls.size());
        assertEquals("http://a", c.urls.get(0));
    }

    @Test
    public void channelUrl_fallsBackWhenIndexOutOfRange() {
        Channel c = new Channel("x");
        c.addUrl("http://a");
        c.addUrl("http://b");
        c.lineIndex = 99;
        assertEquals("http://a", c.url());
    }

    @Test
    public void channelCopy_isIndependent() {
        Channel c = new Channel("x");
        c.addUrl("http://a");
        c.headers.put("Referer", "http://r");
        Channel copy = c.copy("新分组");
        assertNotSame(c, copy);
        assertEquals("新分组", copy.group);
        assertEquals(c.urls, copy.urls);
        assertEquals(c.headers, copy.headers);
        copy.addUrl("http://b");
        assertEquals(1, c.urls.size());
    }

    @Test
    public void playlistEmpty_andCount() {
        Playlist p = new Playlist();
        assertTrue(p.isEmpty());
        Group g = p.group("g");
        g.findOrCreate("ch");
        assertFalse(p.isEmpty());
        assertEquals(1, p.channelCount());
        assertEquals(1, p.allChannels().size());
    }
}
