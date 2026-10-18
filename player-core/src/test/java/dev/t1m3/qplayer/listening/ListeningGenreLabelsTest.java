package dev.t1m3.qplayer.listening;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class ListeningGenreLabelsTest {
    @Test public void onlyRequestedLabelsAreAcceptedEvenIfResponseInventsStatistics() {
        Map<String,String> labels = ListeningGenreLabels.parse("```json\n{\"totalSeconds\":99999,\"genres\":[[\"a\",\"rnb\"],[\"not-requested\",\"摇滚\"],[\"b\",\"未知\"]]}\n```", new HashSet<>(Arrays.asList("a","b")));
        assertEquals(Collections.singletonMap("a", "R&B"), labels);
    }
    @Test public void malformedOrExplanatoryOutputDoesNotBecomeClassification() {
        assertTrue(ListeningGenreLabels.parse("not json", Collections.singleton("a")).isEmpty());
        assertTrue(ListeningGenreLabels.parse("{\"genres\":[[\"a\",{}]]}", Collections.singleton("a")).isEmpty());
        assertTrue(ListeningGenreLabels.parse("{\"genres\":[[\"a\",\"\"]]}", Collections.singleton("a")).isEmpty());
    }
}
