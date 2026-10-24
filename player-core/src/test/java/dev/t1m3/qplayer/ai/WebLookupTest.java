package dev.t1m3.qplayer.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebLookupTest {
    @Test public void verifiedSearchRequestsInlineContentAndWorksWithoutFetchService() throws Exception {
        AtomicReference<JsonObject> sent = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/tools/search", exchange -> {
            sent.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            JsonObject row = new JsonObject(); row.addProperty("title", "Coldplay album preview");
            row.addProperty("url", "https://www.grammy.com/news/coldplay-test");
            row.addProperty("content", "Published on October 21, 2026\nColdplay announced a listening event next month for their new album, which remains a preview rather than a release.");
            com.google.gson.JsonArray rows = new com.google.gson.JsonArray(); rows.add(row);
            JsonObject response = new JsonObject(); response.add("results", rows);
            byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            JsonObject result = JsonParser.parseString(WebLookup.verified(base + "/v1/tools/search", "", base, "test-key",
                    "Coldplay", 1000, java.time.LocalDate.of(2026, 10, 21), java.time.ZoneId.of("Asia/Shanghai"))).getAsJsonObject();
            assertTrue(sent.get().get("need_content").getAsBoolean());
            assertEquals(1, result.getAsJsonArray("news").size());
            assertTrue(result.toString().contains("preview"));
            assertFalse(result.toString().contains("test-key"));
        } finally { server.stop(0); }
    }
    @Test public void verificationFetchesOriginalPageAndDoesNotTrustFreshSearchSummary() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> requested = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/tools/search", exchange -> {
            byte[] response = ("{\"results\":[{\"title\":\"Coldplay 新专辑\",\"url\":\"https://www.grammy.com/news/coldplay-test\","
                    + "\"date\":\"2026-10-21T02:00:00Z\",\"summary\":\"今天已经发行\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.createContext("/v1/tools/fetch", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            ByteArrayOutputStream input = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int n; (n = exchange.getRequestBody().read(buffer)) >= 0;) input.write(buffer, 0, n);
            requested.set(new String(input.toByteArray(), StandardCharsets.UTF_8));
            JsonObject response = new JsonObject();
            response.addProperty("content", "发布时间：2026-10-19\nColdplay 新专辑将在下个月举办试听活动，目前尚未发布，只是未来的预告。");
            byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            JsonObject result = JsonParser.parseString(WebLookup.verified(base + "/v1/tools/search", "", base,
                    "test-token", "今天 Coldplay 新专辑", 1000, java.time.LocalDate.of(2026, 10, 21), java.time.ZoneId.of("Asia/Shanghai"))).getAsJsonObject();
            assertEquals("Bearer test-token", auth.get()); assertTrue(requested.get().contains("https://www.grammy.com/news/coldplay-test"));
            assertEquals(0, result.getAsJsonArray("news").size());
            assertEquals("no_usable_sources", result.get("status").getAsString());
            assertFalse(result.toString().contains("今天已经发行")); assertFalse(result.toString().contains("test-token"));
        } finally { server.stop(0); }
    }
    @Test public void officialBareV1AndFullChatAddressUseDocumentedSearchEndpoint() {
        for (String base : new String[]{"https://api.deepseek.com", "https://api.deepseek.com/v1/",
                "https://api.deepseek.com/v1/chat/completions"}) {
            assertEquals("https://api.deepseek.com/v1/tools/search", WebLookup.searchEndpoint("", "", base));
            assertEquals("https://api.deepseek.com/v1/tools/search", WebLookup.searchEndpoint("https://api.tavily.com/search", "", base));
        }
        assertEquals("https://api.tavily.com/search", WebLookup.searchEndpoint("https://api.tavily.com/search", "tavily-key", "https://api.deepseek.com"));
        assertEquals("", WebLookup.searchEndpoint("", "", "https://relay.example/v1"));
        assertFalse(WebLookup.isDeepSeekSearch("https://api.deepseek.com.evil.example/search"));
        assertFalse(WebLookup.isDeepSeekSearch("https://relay.example/search?foo=deepseek.com"));
    }
    @Test public void realHttpContractIncludesBearerAndParsesDatedSourcesWithoutFetchingFullPages() throws Exception {
        AtomicReference<JsonObject> sent = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/tools/search", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] bytes = new byte[1024]; int n;
            while ((n = exchange.getRequestBody().read(bytes)) >= 0) out.write(bytes, 0, n);
            sent.set(JsonParser.parseString(new String(out.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject());
            byte[] response = ("{\"results\":[{\"title\":\"新专辑\",\"url\":\"https://artist.example/news\","
                    + "\"date\":\"2026-10-21T10:00:00Z\",\"summary\":\"音乐人宣布发行\",\"content\":\"不读取整页\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            String query = String.join("", java.util.Collections.nCopies(150, "🎵"));
            WebLookup.Result result = WebLookup.lookup(base + "/v1/tools/search", "", base + "/v1", "test-key", query, 5000);
            assertTrue(result.ok); assertEquals(200, result.httpStatus);
            assertEquals("Bearer test-key", auth.get());
            assertEquals(128, sent.get().get("query").getAsString().codePointCount(0, sent.get().get("query").getAsString().length()));
            assertEquals(5, sent.get().get("count").getAsInt()); assertFalse(sent.get().get("need_content").getAsBoolean());
            assertTrue(result.context.contains("https://artist.example/news"));
            assertTrue(result.context.contains("2026-10-21")); assertTrue(result.context.contains("音乐人宣布发行"));
            assertFalse(result.context.contains("不读取整页"));
            WebLookup.Result noKey = WebLookup.lookup(base + "/v1/tools/search", "", "https://other.example", "private-chat-key", "test", 5000);
            assertFalse(noKey.ok); assertEquals(0, noKey.httpStatus);
        } finally { server.stop(0); }
    }
    @Test public void failuresEmptyResultsAndMalformedResponsesRemainDistinguishable() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> body = new AtomicReference<>("{\"results\":[]}");
        java.util.concurrent.atomic.AtomicInteger code = new java.util.concurrent.atomic.AtomicInteger(200);
        server.createContext("/tools/search", exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code.get(), bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/tools/search";
            WebLookup.Result empty = WebLookup.lookup(url, "test-key", "", "", "天气", 1000);
            assertTrue(empty.ok); assertEquals("", empty.context); assertTrue(empty.status.contains("暂无结果"));
            body.set("{}"); assertFalse(WebLookup.lookup(url, "test-key", "", "", "天气", 1000).ok);
            for (int status : new int[]{401, 402, 429, 500}) {
                code.set(status); body.set("{\"error\":{\"message\":\"do not leak test-key\"}}");
                WebLookup.Result failure = WebLookup.lookup(url, "test-key", "", "", "天气", 1000);
                assertFalse(failure.ok); assertEquals(status, failure.httpStatus); assertEquals("", failure.context);
                assertFalse(failure.status.contains("test-key"));
            }
        } finally { server.stop(0); }
    }
}
