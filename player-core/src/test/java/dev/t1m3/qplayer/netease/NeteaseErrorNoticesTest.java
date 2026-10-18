package dev.t1m3.qplayer.netease;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Optional requests must not emit foreground errors or silence another lane. */
public class NeteaseErrorNoticesTest {
    @Test public void backgroundFailureDoesNotConsumeForegroundError() {
        NeteaseClient.ErrorNotices notices = new NeteaseClient.ErrorNotices();
        List<String> shown = new ArrayList<>();
        notices.listener = shown::add;
        notices.withoutNotices(() -> notices.report("参数错误"));
        notices.report("参数错误");
        notices.report("参数错误");
        assertEquals(Collections.singletonList("参数错误"), shown);
    }

    @Test public void nestedBackgroundScopesRestoreTheOuterScope() {
        NeteaseClient.ErrorNotices notices = new NeteaseClient.ErrorNotices();
        List<String> shown = new ArrayList<>();
        notices.listener = shown::add;
        notices.withoutNotices(() -> {
            notices.withoutNotices(() -> notices.report("内层失败"));
            notices.report("外层失败");
        });
        notices.report("收藏失败");
        assertEquals(Collections.singletonList("收藏失败"), shown);
    }

    @Test public void throwingBackgroundWorkDoesNotSilenceTheNextUserAction() {
        NeteaseClient.ErrorNotices notices = new NeteaseClient.ErrorNotices();
        List<String> shown = new ArrayList<>();
        notices.listener = shown::add;
        try {
            notices.withoutNotices(() -> { throw new IllegalStateException("failed shelf"); });
            fail("expected failure");
        } catch (IllegalStateException expected) {
            assertEquals("failed shelf", expected.getMessage());
        }
        notices.report("播放失败");
        assertEquals(Collections.singletonList("播放失败"), shown);
    }

    @Test public void simultaneousForegroundRequestStillReportsItsError() throws Exception {
        NeteaseClient.ErrorNotices notices = new NeteaseClient.ErrorNotices();
        List<String> shown = Collections.synchronizedList(new ArrayList<>());
        notices.listener = shown::add;
        CountDownLatch backgroundStarted = new CountDownLatch(1);
        CountDownLatch foregroundFinished = new CountDownLatch(1);
        Thread background = new Thread(() -> notices.withoutNotices(() -> {
            backgroundStarted.countDown();
            try {
                if (!foregroundFinished.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout");
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            notices.report("后台参数错误");
        }));
        background.start();
        try {
            assertTrue(backgroundStarted.await(5, TimeUnit.SECONDS));
            notices.report("用户歌单无权访问");
        } finally {
            foregroundFinished.countDown();
            background.join(5000);
        }
        assertFalse(background.isAlive());
        assertEquals(Collections.singletonList("用户歌单无权访问"), shown);
    }
}
