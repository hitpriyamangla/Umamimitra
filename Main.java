package com.umamimitra.galley;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * UmamiMitra - version 2 backend.
 * One small web server for all screens. They share ONE MealService.
 *
 *   GET  /api/meals      which meals can this passenger have? (+ custom plate if none)
 *   POST /api/expand     what ingredient words does a typed allergy mean?
 *   POST /api/parse      read a typed request
 *   POST /api/reserve    reserve a meal / custom plate / crew arrangement
 *   GET  /api/requests   all requests
 *   GET  /api/inventory  meals and galley components with stock
 *   GET  /api/planner    waste planner: what to load next flight
 *   POST /api/simulate   demo: simulate boarding passengers
 *   POST /api/approve    catering approves the next-flight load
 *   GET  /api/passport   a Dietary Passport by login id (id=...);  POST /api/passport/save  create or update it
 *   POST /api/manifest/seed  demo: a flight full of passengers with passports (some pre-ordered)
 *   GET  /api/forecast   AI model 2: expected demand, custom plates needed, extras to stock (explain=1 asks Claude to word it)
 *   POST /api/status     crew: ACKNOWLEDGED or SERVED
 *   POST /api/reset      fresh demo
 *
 * In-flight (Bluetooth) routes. The seat hub calls these; they stand in for BLE characteristics:
 *   POST /api/ble/frame  one Bluetooth frame (m=message id, i=frame no, n=frame count, d=hex data)
 *   GET  /api/ble/read   the passenger's current request for a seat (seat=18A)
 *   GET  /api/ble/log    live gateway log and counters (monitor page)
 *   GET  /api/analysis   AI allergen analysis of every meal
 */
public class Main {

    private static final MealService service = new MealService();
    private static final BleGateway gateway = new BleGateway(service);
    /** Folder with the galley screens (web/galley). Found automatically, so IntelliJ's working directory does not matter. */
    private static final Path WEB_ROOT = com.umamimitra.link.Folders.find("web/galley", "galley.web");

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "3000"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));

        server.createContext("/api/meals", safe(Main::handleMeals));
        server.createContext("/api/expand", safe(Main::handleExpand));
        server.createContext("/api/parse", safe(ex -> sendJson(ex, 200, RequestParser.parse(readQuery(ex).getOrDefault("text", "")).toJson())));
        server.createContext("/api/reserve", safe(Main::handleReserve));
        server.createContext("/api/requests", safe(Main::handleRequests));
        server.createContext("/api/inventory", safe(Main::handleInventory));
        server.createContext("/api/planner", safe(ex -> sendJson(ex, 200, service.plannerJson())));
        server.createContext("/api/simulate", safe(ex -> {
            int n = Math.min(300, Integer.parseInt(readQuery(ex).getOrDefault("n", "100")));
            service.simulate(n);
            sendJson(ex, 200, "{\"ok\":true}");
        }));
        server.createContext("/api/approve", safe(ex -> {
            // plan=thali:29,paneer:20  ->  {thali=29, paneer=20}
            Map<String, Integer> plan = new HashMap<>();
            for (String part : splitList(readQuery(ex).get("plan"))) {
                String[] kv = part.split(":");
                if (kv.length == 2) plan.put(kv[0], Integer.parseInt(kv[1].trim()));
            }
            service.approvePlan(plan);
            sendJson(ex, 200, "{\"ok\":true}");
        }));
        server.createContext("/api/status", safe(Main::handleStatus));
        server.createContext("/api/reset", safe(ex -> { service.reset(); gateway.reset(); sendJson(ex, 200, "{\"ok\":true}"); }));

        server.createContext("/api/ble/frame", safe(ex -> {
            Map<String, String> q = readQuery(ex);
            try {
                sendJson(ex, 200, gateway.onFrame(Integer.parseInt(q.getOrDefault("m", "-1")),
                        Integer.parseInt(q.getOrDefault("i", "-1")), Integer.parseInt(q.getOrDefault("n", "0")),
                        q.getOrDefault("d", "")));
            } catch (IllegalArgumentException e) {      // includes bad numbers and bad hex
                sendJson(ex, 400, "{\"error\":" + Json.quote(String.valueOf(e.getMessage())) + "}");
            }
        }));
        server.createContext("/api/ble/read", safe(ex -> {
            Map<String, String> q = readQuery(ex);
            sendJson(ex, 200, service.latestJsonFor(q.getOrDefault("seat", ""), q.getOrDefault("pid", "")));
        }));
        server.createContext("/api/passport", safe(Main::handlePassport));
        server.createContext("/api/manifest/seed", safe(ex -> {
            Map<String, String> q = readQuery(ex);
            service.seedManifest(Math.min(300, Integer.parseInt(q.getOrDefault("n", "150"))),
                                 Math.min(100, Integer.parseInt(q.getOrDefault("pre", "55"))));
            gateway.reset();
            sendJson(ex, 200, "{\"ok\":true}");
        }));
        server.createContext("/api/forecast", safe(ex ->
            sendJson(ex, 200, service.forecastJson(readQuery(ex).getOrDefault("explain", "0").equals("1")))));
        server.createContext("/api/ble/log", safe(ex -> sendJson(ex, 200, gateway.logJson())));
        server.createContext("/api/analysis", safe(ex -> sendJson(ex, 200, service.analysisJson())));
        server.createContext("/", Main::handleFile);

        server.start();
        System.out.println("UmamiMitra running at http://localhost:" + port
            + "   (AI: " + (Claude.enabled() ? "Claude" : "built-in rules") + ")");
    }

    // ---------- Passenger ----------

    private static void handleMeals(HttpExchange ex) throws IOException {
        Restrictions r = restrictionsFrom(readQuery(ex));
        MealService.SearchResult result = service.search(r);

        StringBuilder json = new StringBuilder("{\"available\":[");
        for (int i = 0; i < result.available.size(); i++) {
            if (i > 0) json.append(",");
            Meal m = result.available.get(i);
            json.append(m.toJson(service.spiceMatches(m, r.spice), service.primaryMatch(m, r)));
        }
        json.append("],\"removed\":[");
        for (int i = 0; i < result.removed.size(); i++) {
            if (i > 0) json.append(",");
            MealService.Removed x = result.removed.get(i);
            json.append("{\"name\":").append(Json.quote(x.meal.name))
                .append(",\"reason\":").append(Json.quote(x.reason)).append("}");
        }
        json.append("],\"plate\":");
        if (!result.plateTried) {
            json.append("null");
        } else if (result.plate == null) {
            json.append("{\"possible\":false}");
        } else {
            double cost = 0;
            int kcal = 0, protein = 0, carbs = 0, fat = 0, fibre = 0;
            StringBuilder items = new StringBuilder();
            for (int i = 0; i < result.plate.size(); i++) {
                Meal c = result.plate.get(i);
                if (i > 0) items.append(",");
                items.append(Json.quote(c.name));
                cost += c.cost; kcal += c.kcal; protein += c.protein; carbs += c.carbs; fat += c.fat; fibre += c.fibre;
            }
            json.append("{\"possible\":true,\"items\":[").append(items).append("],\"cost\":").append(cost)
                .append(",\"nutrition\":{\"kcal\":").append(kcal).append(",\"protein\":").append(protein)
                .append(",\"carbs\":").append(carbs).append(",\"fat\":").append(fat).append(",\"fibre\":").append(fibre).append("}}");
        }
        boolean verify = !r.allergies.isEmpty() || !r.custom.isEmpty();
        json.append(",\"verify\":").append(verify).append("}");
        sendJson(ex, 200, json.toString());
    }

    /** GET /api/passport?id=...  reads a passport.   POST /api/passport/save?id=...&seat=...&(preferences)  creates or updates it. */
    private static void handlePassport(HttpExchange ex) throws IOException {
        Map<String, String> q = readQuery(ex);
        String id = q.getOrDefault("id", "").trim();
        if (id.length() < 3) { sendJson(ex, 400, "{\"error\":\"login id needed\"}"); return; }
        if (ex.getRequestURI().getPath().endsWith("/save")) {
            Passport p = service.savePassport(id, q.getOrDefault("seat", ""), q.getOrDefault("name", ""), restrictionsFrom(q), "APP", null);
            sendJson(ex, 200, p.toJson());
            return;
        }
        Passport p = service.getPassport(id);
        sendJson(ex, 200, p == null ? "{\"found\":false}" : p.toJson());
    }

    private static void handleExpand(HttpExchange ex) throws IOException {
        String text = readQuery(ex).getOrDefault("text", "");
        AllergenExpander.Expansion e = AllergenExpander.expand(text);
        sendJson(ex, 200, "{\"keywords\":" + Json.array(e.keywords) + ",\"source\":" + Json.quote(e.source) + "}");
    }

    private static void handleReserve(HttpExchange ex) throws IOException {
        Map<String, String> q = readQuery(ex);
        Request r = service.reserve(q.getOrDefault("mealId", ""), q.getOrDefault("seat", "18A"), restrictionsFrom(q));

        if (r == null) {
            sendJson(ex, 409, "{\"success\":false,\"message\":\"Sorry, that option is not available for you.\"}");
        } else {
            String message = r.kind.equals("CREW")
                ? "Request sent. The crew will arrange a safe alternative for seat " + r.seat + "."
                : r.mealName + " reserved for seat " + r.seat + ".";
            String pid = q.getOrDefault("pid", "").trim();
            String passport = "null";
            if (pid.length() >= 3) {      // booking a meal = pre-order: it is saved in the Dietary Passport too
                Passport pp = service.savePassport(pid, r.seat, q.getOrDefault("name", ""), restrictionsFrom(q), "APP", r.mealName);
                passport = "{\"id\":" + Json.quote(pp.id) + ",\"version\":" + pp.version + "}";
            }
            sendJson(ex, 200, "{\"success\":true,\"id\":" + r.id + ",\"kcal\":" + r.kcal + ",\"passport\":" + passport
                + ",\"message\":" + Json.quote(message) + "}");
        }
    }

    // ---------- Catering and crew ----------

    private static void handleRequests(HttpExchange ex) throws IOException {
        StringBuilder json = new StringBuilder("[");
        List<Request> list = service.getRequests();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) json.append(",");
            json.append(list.get(i).toJson());
        }
        sendJson(ex, 200, json.append("]").toString());
    }

    private static void handleInventory(HttpExchange ex) throws IOException {
        sendJson(ex, 200, "{\"meals\":" + itemsJson(service.getMenu())
                        + ",\"components\":" + itemsJson(service.getComponents()) + "}");
    }

    private static String itemsJson(List<Meal> items) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) json.append(",");
            json.append(items.get(i).toJson(true));
        }
        return json.append("]").toString();
    }

    private static void handleStatus(HttpExchange ex) throws IOException {
        Map<String, String> q = readQuery(ex);
        String status = q.getOrDefault("status", "");
        if (!status.equals("ACKNOWLEDGED") && !status.equals("SERVED")) {
            sendJson(ex, 400, "{\"error\":\"bad status\"}");
            return;
        }
        Request r = service.setStatus(Integer.parseInt(q.getOrDefault("id", "0")), status);
        sendJson(ex, r == null ? 404 : 200, r == null ? "{\"error\":\"unknown request\"}" : r.toJson());
    }

    // ---------- Reading the passenger's choices ----------

    /** Turns the web address query into a Restrictions object (expanding typed allergies). */
    private static Restrictions restrictionsFrom(Map<String, String> q) {
        Restrictions r = new Restrictions();
        r.diet = q.getOrDefault("diet", "vegetarian");
        r.noBeef = q.getOrDefault("noBeef", "false").equals("true");
        r.noPork = q.getOrDefault("noPork", "false").equals("true");
        r.spice = q.getOrDefault("spice", "mild");
        r.likes = splitList(q.get("likes"));
        if (r.likes.size() > 3) r.likes = new ArrayList<>(r.likes.subList(0, 3));
        r.severe = q.getOrDefault("severe", "false").equals("true");
        r.allergies = splitList(q.get("allergies"));
        for (String typed : splitList(q.get("custom"))) {
            if (r.custom.size() < 5) r.custom.put(typed.toLowerCase(), AllergenExpander.expand(typed).keywords);
        }
        return r.normalize();
    }

    // ---------- Serving the pages ----------

    private static void handleFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";

        Path root = WEB_ROOT;
        Path file = root.resolve(path.substring(1)).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            send(ex, 404, "text/plain", "Not found".getBytes());
            return;
        }
        String type = path.endsWith(".css") ? "text/css"
                    : path.endsWith(".js") ? "application/javascript" : "text/html";
        send(ex, 200, type + "; charset=utf-8", Files.readAllBytes(file));
    }

    // ---------- Helpers ----------

    private interface Handler { void run(HttpExchange ex) throws Exception; }

    /** Wraps a handler so one bug returns an error instead of crashing the server. */
    private static HttpHandler safe(Handler h) {
        return ex -> {
            try {
                h.run(ex);
            } catch (Exception e) {
                e.printStackTrace();
                sendJson(ex, 500, "{\"error\":" + Json.quote(String.valueOf(e.getMessage())) + "}");
            }
        };
    }

    private static List<String> splitList(String text) {
        List<String> list = new ArrayList<>();
        if (text == null) return list;
        for (String s : text.split(",")) {
            if (!s.isBlank()) list.add(s.trim());
        }
        return list;
    }

    private static Map<String, String> readQuery(HttpExchange ex) {
        Map<String, String> map = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return map;
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            map.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return map;
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        send(ex, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(data); }
    }
}
