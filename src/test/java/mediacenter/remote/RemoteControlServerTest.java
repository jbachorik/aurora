package mediacenter.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RemoteControlServerTest {

    /** Remembers what it was asked instead of launching anything. */
    private static final class FakeKiosk implements RemoteKiosk {
        volatile String opened;
        volatile String watched;
        volatile Optional<String> complaint = Optional.empty();

        @Override
        public Optional<String> openUrl(String url) {
            if (complaint.isPresent()) {
                return complaint;
            }
            opened = url;
            return Optional.empty();
        }

        @Override
        public Optional<String> watchUrl(String url) {
            if (complaint.isPresent()) {
                return complaint;
            }
            watched = url;
            return Optional.empty();
        }

        @Override
        public boolean stopBrowser() {
            boolean wasOpen = opened != null;
            opened = null;
            return wasOpen;
        }

        @Override
        public Optional<String> currentUrl() {
            return Optional.ofNullable(opened);
        }
    }

    private FakeKiosk kiosk;
    private RemoteControlServer server;
    private HttpClient client;

    @BeforeEach
    void startServer() throws Exception {
        kiosk = new FakeKiosk();
        server = new RemoteControlServer(kiosk, 0, null);
        server.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stopServer() {
        server.stop();
        client.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri(path))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    @Test
    @DisplayName("the root serves the web UI")
    void servesTheIndexPage() throws Exception {
        HttpResponse<String> response = get("/");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Aurora"));
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
    }

    @Test
    @DisplayName("a path nobody serves is a plain 404")
    void unknownPathIsNotFound() throws Exception {
        assertEquals(404, get("/nothing/here").statusCode());
    }

    @Test
    @DisplayName("opening hands the address to the kiosk")
    void openReachesTheKiosk() throws Exception {
        HttpResponse<String> response = post("/api/open", "{\"url\": \"https://example.com\"}");
        assertEquals(200, response.statusCode());
        assertEquals("https://example.com", kiosk.opened);
    }

    @Test
    @DisplayName("the kiosk's complaint comes back as a 409 with the reason")
    void openComplaintIsRelayed() throws Exception {
        kiosk.complaint = Optional.of("No browser is configured");
        HttpResponse<String> response = post("/api/open", "{\"url\": \"https://example.com\"}");
        assertEquals(409, response.statusCode());
        assertTrue(response.body().contains("No browser is configured"));
        assertNull(kiosk.opened);
    }

    @Test
    @DisplayName("a body that is not JSON, or has no url, is a 400")
    void openRejectsBadBodies() throws Exception {
        assertEquals(400, post("/api/open", "not json").statusCode());
        assertEquals(400, post("/api/open", "{}").statusCode());
        assertEquals(400, post("/api/open", "{\"url\": \"   \"}").statusCode());
        assertNull(kiosk.opened);
    }

    @Test
    @DisplayName("watching hands the address to the kiosk's resolver")
    void watchReachesTheKiosk() throws Exception {
        HttpResponse<String> response =
                post("/api/watch", "{\"url\": \"https://cinema.mosfilm.ru/film\"}");
        assertEquals(200, response.statusCode());
        assertEquals("https://cinema.mosfilm.ru/film", kiosk.watched);
        assertNull(kiosk.opened);
    }

    @Test
    @DisplayName("a page that resolves to nothing comes back as a 409 with the reason")
    void watchComplaintIsRelayed() throws Exception {
        kiosk.complaint = Optional.of("No playable stream was found on this page.");
        HttpResponse<String> response = post("/api/watch", "{\"url\": \"https://example.com\"}");
        assertEquals(409, response.statusCode());
        assertTrue(response.body().contains("No playable stream"));
        assertNull(kiosk.watched);
    }

    @Test
    @DisplayName("stop says whether there was anything to stop")
    void stopReportsWhatItDid() throws Exception {
        kiosk.opened = "https://example.com";
        assertTrue(post("/api/stop", "").body().contains("true"));
        assertTrue(post("/api/stop", "").body().contains("false"));
    }

    @Test
    @DisplayName("status mirrors what the kiosk is showing")
    void statusFollowsTheKiosk() throws Exception {
        assertTrue(get("/api/status").body().contains("\"active\": false"));
        kiosk.opened = "https://example.com";
        String body = get("/api/status").body();
        assertTrue(body.contains("\"active\": true"));
        assertTrue(body.contains("https://example.com"));
    }

    @Test
    @DisplayName("the API endpoints insist on their methods")
    void wrongMethodsAreRefused() throws Exception {
        assertEquals(405, get("/api/open").statusCode());
        assertEquals(405, get("/api/watch").statusCode());
        assertEquals(405, get("/api/stop").statusCode());
        assertEquals(405, post("/api/status", "").statusCode());
    }
}
