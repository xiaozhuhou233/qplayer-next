package dev.t1m3.qplayer.netease;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class NeteaseReportProgressTest {
    @Test public void pauseResumeAndFinalSwitchUploadOnlyNewListening() throws Exception {
        List<String> events = new ArrayList<>();
        NeteaseReportProgress report = new NeteaseReportProgress((seconds, start) -> events.add(start ? "start" : "heard:" + seconds));
        report.started(); report.started(); report.checkpoint(20); report.checkpoint(20);
        report.started(); report.checkpoint(35); report.checkpoint(35);
        assertEquals(Arrays.asList("start", "heard:20", "start", "heard:15"), events);
    }

    @Test public void shortPausesAccumulateWithoutDroppingOrInflatingHeardTime() throws Exception {
        List<Long> heard = new ArrayList<>();
        NeteaseReportProgress report = new NeteaseReportProgress((seconds, start) -> { if (!start) heard.add(seconds); });
        report.started(); report.checkpoint(1); report.started(); report.checkpoint(2);
        report.started(); report.checkpoint(4); report.started(); report.checkpoint(5); report.checkpoint(8);
        assertEquals(Arrays.asList(4L, 4L), heard);
    }

    @Test public void rejectedUploadsAreNotMarkedAcknowledged() throws Exception {
        List<String> events = new ArrayList<>();
        boolean[] fail = {true};
        NeteaseReportProgress report = new NeteaseReportProgress((seconds, start) -> {
            if (!start && fail[0]) { fail[0] = false; throw new IOException("rejected"); }
            events.add(start ? "start" : "heard:" + seconds);
        });
        report.started();
        try { report.checkpoint(30); fail(); } catch (IOException expected) { }
        report.checkpoint(35); report.checkpoint(35);
        assertEquals(Arrays.asList("start", "heard:35"), events);
    }
}
