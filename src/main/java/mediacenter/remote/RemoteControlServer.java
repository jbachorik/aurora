package mediacenter.remote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import mediacenter.json.Json;
import mediacenter.json.JsonException;
import mediacenter.json.JsonValue;
import mediacenter.json.JsonValue.JsonBoolean;
import mediacenter.json.JsonValue.JsonObject;
import mediacenter.json.JsonValue.JsonString;

/**
 * The remote control: a small HTTP server so a phone on the same network can
 * steer the media center.
 *
 * <p>Serves a one-page web UI at {@code /} and a JSON API underneath it:
 *
 * <ul>
 *   <li>{@code GET  /api/status} — what the kiosk browser is showing</li>
 *   <li>{@code POST /api/open}   — {@code {"url": "…"}}, open it full screen</li>
 *   <li>{@code POST /api/stop}   — close the browser, back to the main menu</li>
 * </ul>
 *
 * <p>Built on the JDK's own {@code jdk.httpserver} so the runtime image grows
 * by one platform module and no dependency. There is deliberately no
 * authentication: the server is reachable only from the home network the
 * television sits on, and the worst a caller can do is open a web page.
 */
public final class RemoteControlServer {

    private static final Logger LOG = Logger.getLogger(RemoteControlServer.class.getName());

    public static final int DEFAULT_PORT = 8765;

    /** More than any address needs; a larger body is refused unread. */
    private static final int MAX_REQUEST_BYTES = 16 * 1024;

    private final HttpServer server;
    private final RemoteKiosk kiosk;
    private final byte[] indexPage;

    /**
     * @param port TCP port to listen on; 0 lets the system pick one (tests)
     * @param executor runs the request handlers; null serves on the server's
     *                 own dispatcher thread, which is plenty for one phone
     */
    public RemoteControlServer(RemoteKiosk kiosk, int port, Executor executor) throws IOException {
        this.kiosk = kiosk;
        this.indexPage = loadIndexPage();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handleIndex);
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/open", this::handleOpen);
        server.createContext("/api/stop", this::handleStop);
    }

    public void start() {
        server.start();
        LOG.log(Level.INFO, () -> "Remote control listening on port " + port());
    }

    public void stop() {
        server.stop(0);
    }

    /** The port actually bound, which matters when 0 was asked for. */
    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * The address to put in the QR code: this machine's LAN IPv4 address and
     * the bound port. Empty when the machine has no such address to offer —
     * unplugged, or loopback only.
     */
    public Optional<String> displayAddress() {
        return lanAddress().map(address -> "http://" + address.getHostAddress() + ":" + port() + "/");
    }

    /** The first site-local IPv4 address of an interface that is up. */
    static Optional<InetAddress> lanAddress() {
        try {
            return NetworkInterface.networkInterfaces()
                    .filter(RemoteControlServer::isUsable)
                    .flatMap(NetworkInterface::inetAddresses)
                    .filter(address -> address instanceof Inet4Address && address.isSiteLocalAddress())
                    .findFirst();
        } catch (SocketException e) {
            LOG.log(Level.WARNING, "Could not enumerate network interfaces", e);
            return Optional.empty();
        }
    }

    private static boolean isUsable(NetworkInterface networkInterface) {
        try {
            return networkInterface.isUp() && !networkInterface.isLoopback();
        } catch (SocketException e) {
            return false;
        }
    }

    // -- handlers ------------------------------------------------------------

    private void handleIndex(HttpExchange exchange) throws IOException {
        try (exchange) {
            // The catch-all context also sees every path no other context
            // claims, so anything but the page itself is a plain 404.
            String path = exchange.getRequestURI().getPath();
            if (!path.equals("/") && !path.equals("/index.html")) {
                respond(exchange, 404, "text/plain; charset=utf-8",
                        "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 405, "text/plain; charset=utf-8",
                        "Method not allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(exchange, 200, "text/html; charset=utf-8", indexPage);
        }
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("GET")) {
                respondJson(exchange, 405, error("Use GET"));
                return;
            }
            Optional<String> current = kiosk.currentUrl();
            Map<String, JsonValue> body = new LinkedHashMap<>();
            body.put("active", new JsonBoolean(current.isPresent()));
            current.ifPresent(url -> body.put("url", new JsonString(url)));
            respondJson(exchange, 200, new JsonObject(body));
        }
    }

    private void handleOpen(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("POST")) {
                respondJson(exchange, 405, error("Use POST"));
                return;
            }
            Optional<String> url = readUrl(exchange.getRequestBody());
            if (url.isEmpty()) {
                respondJson(exchange, 400, error("Send {\"url\": \"…\"}"));
                return;
            }
            Optional<String> complaint = kiosk.openUrl(url.get());
            if (complaint.isPresent()) {
                respondJson(exchange, 409, error(complaint.get()));
                return;
            }
            LOG.log(Level.INFO, () -> "Remote request to open " + url.get());
            respondJson(exchange, 200, new JsonObject(Map.of("ok", new JsonBoolean(true))));
        }
    }

    private void handleStop(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("POST")) {
                respondJson(exchange, 405, error("Use POST"));
                return;
            }
            boolean stopped = kiosk.stopBrowser();
            LOG.log(Level.INFO, () -> "Remote stop request, "
                    + (stopped ? "closing the browser" : "nothing was open"));
            respondJson(exchange, 200, new JsonObject(Map.of("stopped", new JsonBoolean(stopped))));
        }
    }

    // -- plumbing ------------------------------------------------------------

    private static Optional<String> readUrl(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(MAX_REQUEST_BYTES);
        try {
            return Json.parseObject(new String(bytes, StandardCharsets.UTF_8))
                    .nonBlankString("url");
        } catch (JsonException e) {
            return Optional.empty();
        }
    }

    private static JsonObject error(String message) {
        return new JsonObject(Map.of("error", new JsonString(message)));
    }

    private static void respondJson(HttpExchange exchange, int status, JsonObject body)
            throws IOException {
        respond(exchange, status, "application/json; charset=utf-8",
                Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] loadIndexPage() throws IOException {
        try (InputStream stream =
                RemoteControlServer.class.getResourceAsStream("/mediacenter/remote/index.html")) {
            if (stream == null) {
                throw new IOException("index.html is missing from the application image");
            }
            return stream.readAllBytes();
        }
    }
}
