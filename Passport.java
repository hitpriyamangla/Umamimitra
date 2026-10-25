package com.umamimitra.galley;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The Dietary Passport: a software profile tied to the passenger's login id (not a paper document).
 * Created when the ticket is booked (diet, allergens, preferences, optional pre-order), reused at every
 * touchpoint, and updated automatically whenever the passenger edits their preferences,
 * on the web before departure or over Bluetooth in flight.
 */
public class Passport {
    String id;                       // login id
    String name = "";
    String seat = "";
    String preOrder = "";            // meal the passenger pre-ordered at booking (name), or ""
    Restrictions prefs = new Restrictions();
    int version = 0;
    String updated = "";
    String via = "BOOKING";          // BOOKING, APP, BLUETOOTH
    boolean pendingSync = false;     // changed on the aircraft: sent to the ground system after landing
    List<String> history = new ArrayList<>();

    Passport(String id) { this.id = id; }

    String toJson() {
        return "{\"found\":true,\"id\":" + Json.quote(id)
             + ",\"name\":" + Json.quote(name)
             + ",\"seat\":" + Json.quote(seat)
             + ",\"preOrder\":" + Json.quote(preOrder)
             + ",\"version\":" + version
             + ",\"updated\":" + Json.quote(updated)
             + ",\"via\":" + Json.quote(via)
             + ",\"pendingSync\":" + pendingSync
             + ",\"prefs\":" + prefs.toJson()
             + ",\"history\":" + Json.array(history) + "}";
    }

    /** What changed between two sets of preferences, in plain words (empty list = nothing changed). */
    static List<String> diff(Restrictions a, Restrictions b) {
        List<String> out = new ArrayList<>();
        if (!a.diet.equals(b.diet)) out.add("diet " + a.diet + " -> " + b.diet);
        if (!a.spice.equals(b.spice)) out.add("spice " + a.spice + " -> " + b.spice);
        if (a.noBeef != b.noBeef) out.add(b.noBeef ? "no beef" : "beef allowed");
        if (a.noPork != b.noPork) out.add(b.noPork ? "no pork" : "pork allowed");
        if (a.severe != b.severe) out.add(b.severe ? "marked severe" : "severe flag removed");
        delta(out, a.allergies, b.allergies, "allergen");
        delta(out, new ArrayList<>(a.custom.keySet()), new ArrayList<>(b.custom.keySet()), "typed allergy");
        delta(out, a.likes, b.likes, "likes");
        return out;
    }

    private static void delta(List<String> out, List<String> before, List<String> after, String what) {
        Set<String> added = new LinkedHashSet<>(after); added.removeAll(before);
        Set<String> removed = new LinkedHashSet<>(before); removed.removeAll(after);
        for (String s : added) out.add("+" + what + " " + s);
        for (String s : removed) out.add("-" + what + " " + s);
    }
}
