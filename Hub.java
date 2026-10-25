package com.umamimitra.galley;

import com.sun.net.httpserver.HttpServer;
import com.umamimitra.link.Args;
import com.umamimitra.link.MiniJson;
import com.umamimitra.link.Radio;
import com.umamimitra.link.TcpRadio;
import com.umamimitra.link.TunnelServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * GALLEY LAPTOP: the link hub.
 *
 * Receives the passenger laptop's calls over the radio, hands them to the Java server on this laptop
 * (http://127.0.0.1:3000) and sends the answers back. Also serves a tiny status page for the galley screen
 * at http://localhost:3002/status.
 *
 * Arguments (all optional):
 *   --port 7777        radio port the passenger laptop connects to
 *   --local            accept the passenger bridge only from this same computer (one-computer test)
 *   --loss 0.15        test mode: drop this fraction of frames
 *   --server URL       the Java server (default http://127.0.0.1:3000)
 *   --status-port 3002
 */
public final class Hub {
    private final String server;
    private final boolean local;
    private final int port, statusPort;
    private final double loss;
    private final Deque<String[]> events = new ArrayDeque<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private TunnelServer tunnel;
    private Radio.Peripheral radio;
    private HttpServer status;

    public Hub(String server, int port, int statusPort, boolean local, double loss) {
        this.server = server; this.port = port; this.statusPort = statusPort; this.local = local; this.loss = loss;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = Args.parse(args);
        Hub hub = new Hub(a.getOrDefault("server", "http://127.0.0.1:3000"), Integer.parseInt(a.getOrDefault("port", "7777")),
                Integer.parseInt(a.getOrDefault("status-port", "3002")), a.containsKey("local"), Double.parseDouble(a.getOrDefault("loss", "0")));
        hub.start();
        Thread.currentThread().join();
    }

    public void start() throws IOException {
        radio = new TcpRadio.Peripheral(port, local, loss);
        tunnel = new TunnelServer(radio, this::forward, this::log);
        radio.start();
        status = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), statusPort), 0);
        status.setExecutor(Executors.newFixedThreadPool(2));
        status.createContext("/status", ex -> {
            byte[] body = statusJson().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        status.start();
        log("Hub listening on port " + port + (local ? " (this computer only)" : "") + (loss > 0 ? ", TEST: dropping " + (int) (loss * 100) + "% of frames" : "")
                + ". Waiting for the passenger laptop...");
    }

    public void stop() {
        if (status != null) status.stop(0);
        if (radio != null) radio.close();
    }

    public TunnelServer tunnel() { return tunnel; }

    private synchronized void log(String msg) {
        String t = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        events.addLast(new String[]{t, msg});
        while (events.size() > 40) events.removeFirst();
        System.out.println(t + " " + msg);
    }

    /** Calls the Java server. Only /api/ paths and GET/POST are allowed. */
    private Map<String, Object> forward(Map<String, Object> req) {
        String method = String.valueOf(req.getOrDefault("m", "GET")), path = String.valueOf(req.getOrDefault("p", ""));
        if (!(method.equals("GET") || method.equals("POST")) || !path.startsWith("/api/")) {
            return reply(403, "application/json", "{\"error\":\"not allowed over the link\"}");
        }
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create(server + path)).timeout(Duration.ofSeconds(20))
                    .method(method, HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return reply(resp.statusCode(), resp.headers().firstValue("Content-Type").orElse("application/json"), resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return reply(502, "application/json", "{\"error\":\"interrupted\"}");
        } catch (Exception e) {
            return reply(502, "application/json", "{\"error\":" + MiniJson.quote("Java server not reachable: " + e) + "}");
        }
    }

    private static Map<String, Object> reply(int status, String type, String body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", status); m.put("t", type); m.put("b", body);
        return m;
    }

    private synchronized String statusJson() {
        StringBuilder ev = new StringBuilder("[");
        int skip = Math.max(0, events.size() - 12), i = 0;
        for (String[] e : events) {
            if (i++ < skip) continue;
            if (ev.length() > 1) ev.append(',');
            ev.append("{\"t\":").append(MiniJson.quote(e[0])).append(",\"m\":").append(MiniJson.quote(e[1])).append('}');
        }
        ev.append(']');
        return "{\"mode\":" + MiniJson.quote(local ? "simulated" : "link")
            + ",\"linked\":" + tunnel.linked()
            + ",\"last\":{\"path\":" + MiniJson.quote(tunnel.lastPath()) + ",\"time\":" + MiniJson.quote(tunnel.lastTime()) + "}"
            + ",\"stats\":{\"requests\":" + tunnel.requests.get() + ",\"replays\":" + tunnel.replays.get() + ",\"bad_crc\":" + tunnel.badCrc.get()
            + ",\"frames_in\":" + tunnel.framesIn.get() + ",\"bytes_out\":" + tunnel.bytesOut.get() + "}"
            + ",\"frame\":" + tunnel.frameSize() + ",\"events\":" + ev + "}";
    }
}
