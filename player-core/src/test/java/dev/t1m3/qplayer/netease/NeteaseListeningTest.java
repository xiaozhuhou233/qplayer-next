package dev.t1m3.qplayer.netease;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.List;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.Test;
import static org.junit.Assert.*;

/** Schema-shaped fixtures, not captures of a logged-in account. */
public class NeteaseListeningTest {
    @Test public void secondsAndMinutesAreNotInterchanged() throws Exception {
        assertEquals(7201, NeteaseListening.parseTotal(json("{code:200,data:{totalDuration:7201}}")));
        NeteaseListening.Report report = NeteaseListening.parseReport(json(
                "{code:200,data:{listenTimeDistributionBlock:{playDuration:120.5,"
                + "durationDetails:[{period:'2026-10-18',duration:1.5}]}}}"));
        assertEquals(7230, report.seconds);
        assertEquals(90, report.days.get(0).seconds);
    }

    @Test public void unknownIsDistinctFromAReportedZero() throws Exception {
        assertEquals(0, NeteaseListening.parseTotal(json("{code:200,data:{totalDuration:0}}")));
        NeteaseListening.Report report = NeteaseListening.parseReport(json(
                "{data:{listenTimeDistributionBlock:{durationDetails:[{period:'2026-10-18',duration:0}]}}}"));
        assertEquals(-1, report.seconds);
        assertEquals(0, report.days.get(0).seconds);
        for (String value : new String[]{"null", "-1", "'bad'", "{}"}) {
            try { NeteaseListening.parseTotal(json("{data:{totalDuration:" + value + "}}")); fail(value); }
            catch (IOException expected) { }
        }
    }

    @Test public void sparseDatesAreExplicitSortedValidatedAndNotDoubleCounted() throws Exception {
        NeteaseListening.Report report = NeteaseListening.parseReport(json(
                "{data:{startTime:'2026-10-01',listenTimeDistributionBlock:{playDuration:10,durationDetails:["
                + "{period:20261018,duration:2},{period:'10-16',duration:1},{period:'2026.10.18',duration:3},"
                + "{period:'2026-02-30',duration:100},{duration:999},{period:'2026-10-17',duration:null},"
                + "{period:'2026-10-15',duration:-1},null]}}}"));
        assertEquals(2, report.days.size());
        assertEquals("2026-10-16", report.days.get(0).date);
        assertEquals("2026-10-18", report.days.get(1).date);
        assertEquals(180, report.days.get(1).seconds);
    }

    @Test public void flatRankSongsKeepRealCountsAndMissingCountsStayUnknown() throws Exception {
        List<NeteaseListening.SongPlay> rows = NeteaseListening.parseRank(json(
                "{data:{songItems:[{songId:1,songName:'A',albumName:'Album',picUrl:'https://example.invalid/a',"
                + "artists:[{name:'Artist'},'Guest'],playCount:7},{songId:2,songName:'B'},"
                + "{songId:1,songName:'Duplicate'},{songId:'bad',songName:'Bad'},null]}}"));
        assertEquals(2, rows.size());
        assertEquals(7, rows.get(0).count);
        assertEquals("Artist / Guest", rows.get(0).song.artist);
        assertEquals("Album", rows.get(0).song.album);
        assertEquals(-1, rows.get(1).count);
        assertEquals(-1, rows.get(0).playedAt);
    }

    @Test public void chronologicalFootprintsRetainTimestampsAndIgnoreOtherResources() throws Exception {
        List<NeteaseListening.SongPlay> rows = NeteaseListening.parseRecent(json(
                "{data:{list:[{resourceType:'SONG',playTime:1000,multiTerminalInfo:{deviceName:'Phone'},"
                + "data:{id:1,name:'A',dt:200000}},{resourceType:'VIDEO',playTime:3000,data:{id:3,name:'Video'}},"
                + "{resourceType:'SONG',playTime:2000,data:{id:1,name:'A'}},"
                + "{resourceType:'SONG',data:{id:'bad',name:'Broken'}},null]}}"));
        assertEquals(2, rows.size());
        assertEquals(2000, rows.get(0).playedAt);
        assertEquals("Phone", rows.get(1).device);
        assertEquals(-1, rows.get(1).count);
    }

    @Test public void permissionFailureCannotBeShownAsZeroOrEmptySuccess() throws Exception {
        for (String response : new String[]{"{code:301,data:null}", "{code:403,message:'会员权限',data:{}}", "{code:200,data:null}"}) {
            try { NeteaseListening.parseReport(json(response)); fail(response); }
            catch (IOException expected) { assertFalse(expected.getMessage().isEmpty()); }
        }
        try { NeteaseListening.parseRank(json("{code:200,data:{}}")); fail("missing songItems"); }
        catch (IOException expected) { }
        assertTrue(NeteaseListening.parseRank(json("{code:200,data:{songItems:[]}}")).isEmpty());
    }

    @Test public void historicalPeriodsUseNeteaseBoundariesAndChinaMidnight() {
        LocalDate today = LocalDate.of(2026, 10, 18);
        NeteaseListening.Period current = NeteaseListening.period("week", 0, today);
        assertEquals("2026-10-18", current.startDate);
        assertEquals("2026-10-24", current.endDate);
        assertEquals(0, current.endTime);
        NeteaseListening.Period previous = NeteaseListening.period("week", -1, today);
        assertEquals("2026-10-11", previous.startDate);
        assertEquals("2026-10-17", previous.endDate);
        assertEquals(LocalDate.of(2026, 10, 17).atStartOfDay(ZoneId.of("Asia/Shanghai"))
                .toInstant().toEpochMilli(), previous.endTime);
        NeteaseListening.Period month = NeteaseListening.period("month", -1, LocalDate.of(2024, 3, 1));
        assertEquals("2024-02-01", month.startDate);
        assertEquals("2024-02-29", month.endDate);
        assertEquals("2025-12-31", NeteaseListening.period("year", 0, today).endDate);
        assertEquals("2024-01-01", NeteaseListening.period("year", -1, today).startDate);
    }

    @Test public void periodsAcrossYearBoundaryKeepTheirActualDates() {
        NeteaseListening.Period period = NeteaseListening.period("week", 0, LocalDate.of(2026, 1, 1));
        assertEquals("2025-12-28", period.startDate);
        assertEquals("2026-01-03", period.endDate);
        assertEquals("2025-12-01", NeteaseListening.period("month", -1, LocalDate.of(2026, 1, 1)).startDate);
        try { NeteaseListening.period("month", 1, LocalDate.now()); fail("future period"); }
        catch (IllegalArgumentException expected) { }
        try { NeteaseListening.period("day", 0, LocalDate.now()); fail("unsupported type"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void partialReportSectionsDoNotRequireAnUnrelatedDurationBlock() throws Exception {
        NeteaseListening.Report report = NeteaseListening.parseReport(json(
                "{data:{topSongBlock:{sections:[{songId:2,songName:'Track',playCount:5}]},"
                + "topArtistBlock:{sections:[{artistId:3,artistName:'Artist',playCount:9},{name:'Unknown'}]},"
                + "musicStyleBlock:{sections:[{styleName:'Pop',percentage:'41%'},{name:'R&B',ratio:0.33},"
                + "{name:'No share'},{name:'Invalid share',percent:101}]}}}"));
        assertEquals(-1, report.seconds);
        assertTrue(report.days.isEmpty());
        assertEquals(2, report.songs.get(0).song.id);
        assertEquals(5, report.songs.get(0).count);
        assertEquals(9, report.artists.get(0).count);
        assertEquals(-1, report.artists.get(1).count);
        assertEquals(41d, report.genres.get(0).percent, .001);
        assertEquals(33d, report.genres.get(1).percent, .001);
        assertEquals(-1d, report.genres.get(2).percent, .001);
        assertEquals(-1d, report.genres.get(3).percent, .001);
    }

    @Test public void songMetadataIsNotUsedToInventArtistOrGenreTotals() throws Exception {
        NeteaseListening.Report report = NeteaseListening.parseReport(json(
                "{data:{topSongBlock:{sections:[{songId:1,songName:'Track',playCount:50,genre:'Pop',"
                + "artists:[{name:'Artist'}]}]}}}"));
        assertEquals(1, report.songs.size());
        assertTrue(report.artists.isEmpty());
        assertTrue(report.genres.isEmpty());
        assertEquals(-1, report.seconds);
        try { NeteaseListening.parseReport(json("{data:{unrelated:{playDuration:999}}}")); fail("missing report"); }
        catch (IOException expected) { }
    }

    @Test public void crossYearShortDayLabelsBelongToTheirReportedYear() throws Exception {
        NeteaseListening.Report report = NeteaseListening.parseReport(json(
                "{data:{startTime:'2025-12-28',endTime:'2026-01-03',listenTimeDistributionBlock:"
                + "{durationDetails:[{period:'12-31',duration:1},{period:'01-01',duration:2}]}}}"));
        assertEquals("2025-12-31", report.days.get(0).date);
        assertEquals("2026-01-01", report.days.get(1).date);
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
}
