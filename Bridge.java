package com.umamimitra.passenger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.umamimitra.link.Args;
import com.umamimitra.link.Folders;
import com.umamimitra.link.MiniJson;
import com.umamimitra.link.Radio;
import com.umamimitra.link.TcpRadio;
import com.umamimitra.link.TunnelClient;
import com.umamimitra.link.Wire;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * PASSENGER LAPTOP: link bridge + local web server.
 *
 * Serves the passenger screens (folder web/passenger) at http://localhost:3001. Every /api/... call the screens
 * make is sent over the link to the galley laptop and the answer comes back the same way.
 *
 * Arguments (all optional):
 *   --hub 192.168.44.1[:7777]   address of the galley laptop (default 127.0.0.1 = same computer, for testing)
 *   --port 3001                 where the passenger screens are served
 *   --frame 180                 largest frame size in bytes (20 = the smallest Bluetooth packet)
 *   --loss 0.15                 test mode: drop this fraction of frames
 */
public final class Bridge {
    private static final long CACHE_MS = 2000;

    private final String hubHost;
    private final int hubPort, webPort, frame;
    private final double loss;
    private final Path web = Folders.find("web/passenger", "passenger.web");

    private final Object stateLock = new Object();
    private volatile boolean connected = false;
    private volatile String device = "", error = "searching for the galley hub...", since = "";
    private volatile TunnelClient tunnel;
    private volatile boolean stopped = false;
    private HttpServer server;

    private final Map<String, Object[]> cache = new ConcurrentHashMap<>();                       // path -> {time, reply}
    private final Map<String, CompletableFuture<Map<String, Object>>> inflight = new ConcurrentHashMap<>();

    public Bridge(String hubHost, int hubPort, int webPort, int frame, double loss) {
        this.hubHost = hubHost; this.hubPort = hubPort; this.webPort = webPort; this.frame = frame; this.loss = loss;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = Args.parse(args);
        String hub = a.getOrDefault("hub", "127.0.0.1");
        String host = hub;
        int port = 7777;
        int colon = hub.lastIndexOf(':');
        if (colon > 0) { host = hub.substring(0, colon); port = Integer.parseInt(hub.substring(colon + 1)); }
        Bridge b = new Bridge(host, port, Integer.parseInt(a.getOrDefault("port", "3001")),
                Integer.parseInt(a.getOrDefault("frame", "180")), Double.parseDouble(a.getOrDefault("loss", "0")));
        b.start();
        Thread.currentThread().join();
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(webPort), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
        log("Passenger screens at http://localhost:" + webPort);
        Thread t = new Thread(this::keepConnected, "bridge-connect");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        stopped = true;
        if (server != null) server.stop(0);
    }

    public boolean isConnected() { return connected; }
    public TunnelClient tunnel() { return tunnel; }

    private static void log(String msg) {
        System.out.println(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + msg);
    }

    // ---------- the link ----------
    /** Connect, send heartbeats, reconnect automatically if the link drops. */
    private void keepConnected() {
        while (!stopped) {
            Radio.Central radio = new TcpRadio.Central(hubHost, hubPort, frame, loss);
            try {
                CountDownLatch dropped = new CountDownLatch(1);
                radio.onDisconnect(dropped::countDown);
                TunnelClient t = new TunnelClient(radio, Bridge::log);
                tunnel = t;
                connected = false;
                error = "searching for the galley hub...";
                radio.connect();
                t.hello();
                device = radio.deviceName();
                error = "";
                since = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                connected = true;
                log("Linked to " + device + " (frame size " + radio.frameLimit() + " bytes)");
                while (!stopped && !dropped.await(10, TimeUnit.SECONDS)) t.hello();     // heartbeat
                if (!stopped) throw new IOException("link dropped");
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                connected = false;
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (!stopped) log("Link: " + error + ". Trying again in 3 s");
            } finally {
                radio.close();
            }
            try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
        }
    }

    /** One web call over the link, with a short cache for GETs and sharing of identical calls in flight. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String method, String path) throws Exception {
        TunnelClient t = tunnel;
        if (method.equals("POST")) {
            cache.clear();
            return t.request(method, path);
        }
        Object[] hit = cache.get(path);
        if (hit != null && System.currentTimeMillis() - (Long) hit[0] < CACHE_MS) return (Map<String, Object>) hit[1];
        CompletableFuture<Map<String, Object>> mine = new CompletableFuture<>();
        CompletableFuture<Map<String, Object>> other = inflight.putIfAbsent(path, mine);
        if (other != null) {
            try { return other.get(60, TimeUnit.SECONDS); }
            catch (ExecutionException e) { throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e; }
        }
        try {
            Map<String, Object> res = t.request(method, path);
            if (res.get("s") instanceof Number && ((Number) res.get("s")).intValue() == 200) cache.put(path, new Object[]{System.currentTimeMillis(), res});
            mine.complete(res);
            return res;
        } catch (Exception e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(path);
        }
    }

    // ---------- the web server ----------
    private void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if (path.equals("/bt/status")) { sendJson(ex, 200, statusJson()); return; }
            if (path.startsWith("/api/")) {
                if (!(method.equals("GET") || method.equals("POST"))) { sendJson(ex, 405, "{\"error\":\"method not allowed\"}"); return; }
                if (!connected || tunnel == null) {
                    sendJson(ex, 503, "{\"error\":\"Link to the galley is down\",\"bluetooth\":false}");
                    return;
                }
                String raw = ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
                try {
                    Map<String, Object> res = call(method, raw);
                    int status = ((Number) res.get("s")).intValue();
                    send(ex, status, String.valueOf(res.getOrDefault("t", "application/json")), String.valueOf(res.get("b")).getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    sendJson(ex, 503, "{\"error\":" + MiniJson.quote("Link: " + e.getMessage()) + ",\"bluetooth\":false}");
                }
                return;
            }
            serveFile(ex, path);
        } catch (RuntimeException e) {
            e.printStackTrace();
            sendJson(ex, 500, "{\"error\":" + MiniJson.quote(String.valueOf(e.getMessage())) + "}");
        }
    }

    private String statusJson() {
        TunnelClient t = tunnel;
        return "{\"connected\":" + connected + ",\"device\":" + MiniJson.quote(device) + ",\"error\":" + MiniJson.quote(error)
            + ",\"since\":" + MiniJson.quote(since) + ",\"linked\":" + (t != null && t.linked())
            + ",\"stats\":{\"requests\":" + (t == null ? 0 : t.requests.get()) + ",\"retries\":" + (t == null ? 0 : t.retries.get())
            + ",\"failed\":" + (t == null ? 0 : t.failed.get()) + "},\"mode\":" + MiniJson.quote(hubHost.equals("127.0.0.1") || hubHost.equals("localhost") ? "simulated" : "link") + "}";
    }

    private void serveFile(HttpExchange ex, String path) throws IOException {
        String rel = path.equals("/") ? "index.html" : path.substring(1);
        Path file = web.resolve(rel).normalize();
        if (!file.startsWith(web) || !Files.isRegularFile(file)) { send(ex, 404, "text/plain", "Not found".getBytes()); return; }
        String name = file.getFileName().toString();
        String type = name.endsWith(".css") ? "text/css; charset=utf-8" : name.endsWith(".js") ? "application/javascript; charset=utf-8"
                    : name.endsWith(".png") ? "image/png" : name.endsWith(".svg") ? "image/svg+xml" : name.endsWith(".json") ? "application/json; charset=utf-8"
                    : "text/html; charset=utf-8";
        send(ex, 200, type, Files.readAllBytes(file));
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        send(ex, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(data); }
    }
}
