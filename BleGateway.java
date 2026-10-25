package com.umamimitra.galley;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.CRC32;

/**
 * The seat-hub side of the Bluetooth link, plus the AI back end it feeds.
 *
 * On the aircraft there is no Wi-Fi, so the passenger's phone talks to a Bluetooth Low Energy (BLE)
 * seat hub. BLE carries only about 20 bytes per packet, so a message is cut into small frames:
 *
 *    frame = message id (2 bytes) + frame number + frame count + up to 16 bytes of data
 *    message = compact text + 4-byte CRC32 check at the end
 *
 * The hub hands every frame to onFrame(). When all frames of a message have arrived and the CRC
 * matches, the message is decoded, the AI reads any free text, every meal is re-checked, and the
 * reply goes back to the phone (a BLE "notify" in the real system).
 *
 * Message format (version 2), fields separated by "|":
 *    UM2 | login id | seat | diet V/G/J/N | flags B,P,S | allergen mask (hex) | spice l/m/s | custom;custom | likes;likes | free text
 *    (version 1, without login id and likes, is still accepted; its spice code "a" means mild)
 *    diet: V vegetarian, G vegan, J jain, N non-veg.  flags: B no beef, P no pork, S severe allergy.
 *    mask: bit i = i-th allergen in AllergenDb.KEYWORDS order (peanuts, treenuts, dairy, egg, gluten, soy,
 *          sesame, fish, shellfish, buckwheat, mustard, coconut).
 */
public class BleGateway {

    private static final String[] ORDER = AllergenDb.KEYWORDS.keySet().toArray(new String[0]);
    private static final long STALE_MS = 60_000;

    private static class Assembly {
        final int total; final byte[][] parts; int got; final long started = System.currentTimeMillis();
        Assembly(int total) { this.total = total; this.parts = new byte[total][]; }
    }

    private final MealService service;
    private final Map<Integer, Assembly> open = new HashMap<>();
    private final Map<Integer, String> done = new LinkedHashMap<>();   // finished messages: replay the reply on a retransmit
    private final Deque<String> log = new ArrayDeque<>();
    private int framesIn, dupFrames, msgsOk, crcErrors, badMsgs;

    public BleGateway(MealService service) { this.service = service; }

    public synchronized void reset() {
        open.clear(); done.clear(); log.clear();
        framesIn = dupFrames = msgsOk = crcErrors = badMsgs = 0;
    }

    // ---------------------------------------------------------------
    //  Frames in
    // ---------------------------------------------------------------

    /** One Bluetooth frame arrived. Returns the JSON the hub sends back (an ack, or the final reply). */
    public synchronized String onFrame(int msgId, int seq, int total, String hex) {
        purge();
        framesIn++;

        String finished = done.get(msgId);
        if (finished != null) {
            dupFrames++;
            note("LINK", "Frame " + (seq + 1) + "/" + total + " of message #" + msgId
                + " arrived again after the message was finished. Replaying the stored reply, nothing is processed twice.");
            return finished;
        }

        if (total < 1 || total > 64) throw new IllegalArgumentException("bad frame count");
        byte[] data = fromHex(hex);
        Assembly a = open.get(msgId);
        if (a == null) {
            a = new Assembly(total);
            open.put(msgId, a);
            note("LINK", "Phone started message #" + msgId + " (" + total + " frames).");
        }
        if (a.total != total || seq < 0 || seq >= total) {
            badMsgs++;
            note("LINK", "Message #" + msgId + ": frame numbers do not fit. Dropped.");
            open.remove(msgId);
            return "{\"error\":\"bad frame\"}";
        }

        if (a.parts[seq] != null) {
            dupFrames++;
            note("FRAME", "Frame " + (seq + 1) + "/" + total + " of #" + msgId + " was a repeat (the phone did not hear our ack). Ignored.");
        } else {
            a.parts[seq] = data;
            a.got++;
            note("FRAME", "Frame " + (seq + 1) + "/" + total + " of #" + msgId + " received (" + data.length + " bytes). "
                + a.got + " of " + total + " present.");
        }
        if (a.got < total) {
            return "{\"ack\":" + seq + ",\"got\":" + a.got + ",\"total\":" + total + "}";
        }

        // All frames present: join, check, decode, think, reply.
        open.remove(msgId);
        String reply = complete(msgId, a);
        done.put(msgId, reply);
        while (done.size() > 50) done.remove(done.keySet().iterator().next());
        return reply;
    }

    private String complete(int msgId, Assembly a) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] part : a.parts) joined.write(part, 0, part.length);
        byte[] all = joined.toByteArray();

        if (all.length < 5) { badMsgs++; return fail(msgId, "message too short"); }
        byte[] body = Arrays.copyOf(all, all.length - 4);
        long sent = 0;
        for (int i = all.length - 4; i < all.length; i++) sent = (sent << 8) | (all[i] & 0xFF);
        CRC32 crc = new CRC32();
        crc.update(body);
        if (crc.getValue() != sent) {
            crcErrors++;
            note("LINK", "Message #" + msgId + ": CRC check FAILED. The data was damaged in the air. Asking the phone to send again.");
            return "{\"complete\":true,\"ok\":false,\"resend\":true,\"error\":\"damaged in transit, please send again\"}";
        }
        note("LINK", "Message #" + msgId + " complete: " + body.length + " bytes, CRC OK.");

        String text = new String(body, StandardCharsets.UTF_8);
        try {
            String reply = process(text);
            msgsOk++;
            return reply;
        } catch (IllegalArgumentException e) {
            badMsgs++;
            return fail(msgId, e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            badMsgs++;
            return fail(msgId, "server error");
        }
    }

    private String fail(int msgId, String why) {
        note("DECODE", "Message #" + msgId + " rejected: " + why);
        return "{\"complete\":true,\"ok\":false,\"error\":" + Json.quote(why) + "}";
    }

    // ---------------------------------------------------------------
    //  Decode, AI, safety check, reply
    // ---------------------------------------------------------------

    private String process(String text) {
        String[] f0 = text.split("\\|", 10);
        String pid = "", likesField = "";
        String[] f;
        if (f0.length >= 9 && f0[0].equals("UM2")) {
            pid = f0[1].trim().toLowerCase();
            if (!pid.matches("[a-z0-9][a-z0-9._@\\-]{2,39}")) throw new IllegalArgumentException("bad login id");
            likesField = f0[8];
            // rebuild the version-1 layout: UM1 | seat | diet | flags | mask | spice | custom | free text
            f = new String[]{"UM1", f0[2], f0[3], f0[4], f0[5], f0[6], f0[7], f0.length > 9 ? f0[9] : ""};
        } else {
            f = text.split("\\|", 8);
            if (f.length < 7 || !f[0].equals("UM1")) throw new IllegalArgumentException("unknown message format");
        }

        String seat = f[1].trim().toUpperCase();
        if (!seat.matches("[0-9]{1,2}[A-K]")) throw new IllegalArgumentException("bad seat number");

        Restrictions r = new Restrictions();
        switch (f[2]) {
            case "V": r.diet = "vegetarian"; break;
            case "G": r.diet = "vegan"; break;
            case "J": r.diet = "jain"; break;
            case "N": r.diet = "nonveg"; break;
            default: throw new IllegalArgumentException("bad diet code");
        }
        r.noBeef = f[3].contains("B");
        r.noPork = f[3].contains("P");
        r.severe = f[3].contains("S");
        int mask;
        try { mask = Integer.parseInt(f[4].isEmpty() ? "0" : f[4], 16); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("bad allergen mask"); }
        for (int i = 0; i < ORDER.length; i++) if ((mask & (1 << i)) != 0) r.allergies.add(ORDER[i]);
        r.spice = f[5].equals("l") ? "low" : f[5].equals("s") ? "spicy" : "mild";
        for (String l : likesField.split(";")) {
            l = l.trim().toLowerCase();
            if (l.matches("[a-z][a-z \\-]{1,19}") && r.likes.size() < 3 && !r.likes.contains(l)) r.likes.add(l);
        }

        List<String> typed = new ArrayList<>();
        for (String c : f[6].split(";")) {
            c = c.trim().toLowerCase();
            if (c.matches("[a-z][a-z \\-]{1,28}") && typed.size() < 5 && !typed.contains(c)) typed.add(c);
        }
        String free = f.length > 7 ? f[7].trim() : "";
        if (free.length() > 300) free = free.substring(0, 300);

        note("DECODE", "Seat " + seat + (pid.isEmpty() ? "" : " (passport " + pid + ")") + ": " + DIETS.get(r.diet) + (r.noBeef ? ", no beef" : "") + (r.noPork ? ", no pork" : "")
            + ", spice " + r.spice
            + ", allergens " + (r.allergies.isEmpty() ? "none ticked" : r.allergies) + (typed.isEmpty() ? "" : ", typed " + typed)
            + (r.likes.isEmpty() ? "" : ", likes " + r.likes)
            + (r.severe ? ", SEVERE" : "") + (free.isEmpty() ? "" : ", plus free text."));

        // ---- AI step 1: read the free text on the server (the phone has no internet) ----
        String source = "rules";
        if (!free.isEmpty()) {
            RequestParser.Parsed p = RequestParser.parse(free);
            source = p.source;
            note("AI", "Read the passenger's own words \"" + free + "\" using " + (p.source.equals("claude") ? "Claude" : "built-in keyword rules") + ".");
            if (mentionsDiet(free)) { r.diet = p.diet; note("AI", "Text states a diet: " + DIETS.get(p.diet) + "."); }
            r.noBeef |= p.noBeef;
            r.noPork |= p.noPork;
            r.severe |= p.severe;
            if (mentionsSpice(free)) r.spice = p.spice;
            for (String l : p.likes) if (r.likes.size() < 3 && !r.likes.contains(l)) { r.likes.add(l); note("AI", "Added food preference from text: " + l + "."); }
            for (String a : p.allergies) {
                if (!r.allergies.contains(a)) { r.allergies.add(a); note("AI", "Added allergen from text: " + AllergenDb.LABELS.get(a) + "."); }
            }
            for (String c : p.custom) {
                String t = c.trim().toLowerCase();
                if (t.matches(".*\\b(serious|severe|very|bad|badly|extreme|extremely|really)\\b.*")) continue;   // severity words, not foods
                if (typed.size() < 5 && !typed.contains(t)) { typed.add(t); note("AI", "Added typed allergy from text: " + t + "."); }
            }
        }

        // ---- AI step 2: turn typed allergies into ingredient words to avoid ----
        List<String> expansions = new ArrayList<>();
        for (String t : typed) {
            AllergenExpander.Expansion e = AllergenExpander.expand(t);
            r.custom.put(t, e.keywords);
            note("AI", "\"" + t + "\" means avoid: " + String.join(", ", e.keywords) + " (" + (e.source.equals("claude") ? "Claude" : "built-in list") + ").");
            expansions.add("{\"term\":" + Json.quote(t) + ",\"keywords\":" + Json.array(e.keywords) + ",\"source\":" + Json.quote(e.source) + "}");
        }

        // A veg diet always means no beef, pork, chicken or fish (also enforced inside the safety check).
        boolean wasVeg = r.isVeg();
        r.normalize();
        if (wasVeg) note("SAFETY", "Diet " + DIETS.get(r.diet) + ": all meat (beef, pork, chicken, fish) is excluded.");

        // ---- Dietary Passport: the edit is saved automatically, so every later touchpoint sees it ----
        Passport pp = null;
        List<String> changes = new ArrayList<>();
        if (!pid.isEmpty()) {
            Passport before = service.getPassport(pid);
            if (before != null) changes = Passport.diff(before.prefs, r);
            pp = service.savePassport(pid, seat, null, r, "BLUETOOTH", null);
            note("PASSPORT", "Passport " + pid + " " + (before == null ? "created" : changes.isEmpty() ? "unchanged" : "updated")
                + " to v" + pp.version + (changes.isEmpty() ? "" : " (" + String.join(", ", changes) + ")")
                + ". Stored on the aircraft server over Bluetooth, no Wi-Fi needed; it syncs to the ground system after landing.");
        }

        // ---- Safety check against every meal (named + AI-found hidden allergens) ----
        MealService.Amendment am = service.amend(seat, r);
        for (MealService.Removed x : am.removed) note("SAFETY", x.meal.name + " removed: " + x.reason + ".");

        String message;
        switch (am.outcome) {
            case "KEPT":
                message = am.request.amended
                    ? "Your preferences are updated. Your meal (" + am.request.mealName + ") is still safe for you. The crew has been told."
                    : "No change needed. Your meal (" + am.request.mealName + ") is still safe for you.";
                break;
            case "CHANGED":
                message = "Your meal was changed because \"" + am.previous + "\" is no longer safe (" + am.why + "). "
                    + "New meal: " + am.request.mealName + (am.request.components.isEmpty() ? "" : " (" + am.request.components + ")")
                    + ". The crew has been told.";
                break;
            case "NEW":
                message = "Booked for seat " + seat + ": " + am.request.mealName
                    + (am.request.components.isEmpty() ? "" : " (" + am.request.components + ")") + ".";
                break;
            default:
                message = "Your meal has already been served, so it cannot be changed here. Please speak to the cabin crew now.";
        }
        note("RESULT", "Seat " + seat + ": " + am.outcome + (am.request != null ? " -> " + am.request.mealName : "") + ". Reply sent to the phone.");

        StringBuilder removed = new StringBuilder("[");
        for (MealService.Removed x : am.removed) {
            if (removed.length() > 1) removed.append(",");
            removed.append("{\"name\":").append(Json.quote(x.meal.name)).append(",\"reason\":").append(Json.quote(x.reason)).append("}");
        }
        removed.append("]");

        return "{\"complete\":true,\"ok\":true"
            + ",\"seat\":" + Json.quote(seat)
            + ",\"outcome\":" + Json.quote(am.outcome)
            + ",\"meal\":" + Json.quote(am.request == null ? "" : am.request.mealName)
            + ",\"components\":" + Json.quote(am.request == null ? "" : am.request.components)
            + ",\"previous\":" + Json.quote(am.previous)
            + ",\"message\":" + Json.quote(message)
            + ",\"removed\":" + removed
            + ",\"expansions\":[" + String.join(",", expansions) + "]"
            + ",\"passport\":" + (pp == null ? "null" : "{\"id\":" + Json.quote(pp.id) + ",\"version\":" + pp.version
                + ",\"updated\":" + Json.quote(pp.updated) + ",\"pendingSync\":" + pp.pendingSync
                + ",\"changes\":" + Json.array(changes) + "}")
            + ",\"kcal\":" + (am.request == null ? 0 : am.request.kcal)
            + ",\"source\":" + Json.quote(source)
            + ",\"verify\":" + (!r.allergies.isEmpty() || !r.custom.isEmpty()) + "}";
    }

    private static final Map<String, String> DIETS = Map.of(
        "vegetarian", "vegetarian", "vegan", "vegan", "jain", "Jain", "nonveg", "non-vegetarian");

    /** Only trust the parser's spice level if the passenger actually mentioned one. */
    private static boolean mentionsSpice(String text) {
        return text.toLowerCase().matches(".*(spic|mild|chili|chilli|\\bhot\\b|\\blow\\b).*");
    }

    /** Only trust the parser's diet if the passenger actually said one (it defaults to non-veg otherwise). */
    private static boolean mentionsDiet(String text) {
        return text.toLowerCase().matches(".*(jain|vegan|vegetarian|\\bveg\\b|non[- ]?veg).*");
    }

    // ---------------------------------------------------------------
    //  Log, stats, helpers
    // ---------------------------------------------------------------

    private void note(String kind, String text) {
        String t = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        log.addLast("{\"t\":" + Json.quote(t) + ",\"k\":" + Json.quote(kind) + ",\"m\":" + Json.quote(text) + "}");
        while (log.size() > 200) log.removeFirst();
    }

    public synchronized String logJson() {
        return "{\"stats\":{\"frames\":" + framesIn + ",\"repeats\":" + dupFrames + ",\"messages\":" + msgsOk
            + ",\"crcErrors\":" + crcErrors + ",\"rejected\":" + badMsgs + ",\"open\":" + open.size() + "}"
            + ",\"ai\":" + Json.quote(Claude.enabled() ? "Claude" : "built-in rules")
            + ",\"log\":[" + String.join(",", log) + "]}";
    }

    private void purge() {
        long now = System.currentTimeMillis();
        open.values().removeIf(a -> now - a.started > STALE_MS);
    }

    private static byte[] fromHex(String hex) {
        if (hex == null || hex.length() % 2 != 0 || hex.length() > 64 || !hex.matches("[0-9a-fA-F]*"))
            throw new IllegalArgumentException("bad frame data");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
