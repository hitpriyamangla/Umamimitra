package com.umamimitra.galley;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The "AI allergen analyst".
 *
 * AllergenDb finds allergens that are NAMED in the ingredient list ("peanut chutney").
 * This class finds the HIDDEN ones: allergens inside sauces and compound ingredients
 * ("tonkatsu sauce" is Worcestershire-style and often contains anchovy; "wasabi" paste is often
 * mixed with mustard; "asafoetida" is usually cut with wheat flour).
 *
 *   Always:        a built-in table of hidden-allergen knowledge (works with no internet on the aircraft).
 *   With a key:    Claude also reads every meal's ingredient list and adds what the table missed.
 *
 * Safety rule: the AI can only ADD a warning. It can never remove one that the rules found.
 * Results are cached, so each meal is analysed once. Claude runs in the background and never blocks.
 */
public class MealAnalyzer {

    public static class Finding {
        final String allergen, ingredient, why, source;
        Finding(String allergen, String ingredient, String why, String source) {
            this.allergen = allergen; this.ingredient = ingredient; this.why = why; this.source = source;
        }
        String toJson() {
            return "{\"allergen\":" + Json.quote(allergen) + ",\"ingredient\":" + Json.quote(ingredient)
                 + ",\"why\":" + Json.quote(why) + ",\"source\":" + Json.quote(source) + "}";
        }
    }

    public static class Analysis {
        final List<Finding> hidden = new CopyOnWriteArrayList<>();
        volatile String source = "rules";       // becomes "claude" once Claude has read this meal
    }

    private static final Map<String, Analysis> cache = new ConcurrentHashMap<>();

    /** {ingredient phrase, allergen key, why} */
    private static final List<String[]> HIDDEN = new ArrayList<>();
    private static void h(String phrase, String allergen, String why) { HIDDEN.add(new String[]{phrase, allergen, why}); }

    static {
        h("tonkatsu sauce", "soy",    "Worcestershire-style sauce is brewed with soy");
        h("tonkatsu sauce", "gluten", "Worcestershire-style sauce usually contains wheat");
        h("tonkatsu sauce", "fish",   "Worcestershire-style sauce often contains anchovy");
        h("soy sauce",      "gluten", "traditional soy sauce is brewed with wheat");
        h("wasabi",         "mustard","prepared wasabi paste is usually horseradish plus mustard");
        h("asafoetida",     "gluten", "asafoetida (hing) is usually blended with wheat flour");
        h("worcestershire", "fish",   "contains anchovy");
        h("furikake",       "sesame", "rice seasoning with sesame");
        h("furikake",       "fish",   "rice seasoning with dried fish");
        h("ponzu",          "soy",    "citrus soy dressing");
        h("curry roux",     "gluten", "roux is thickened with wheat flour");
        h("curry roux",     "dairy",  "roux often contains butter or milk powder");
    }

    // ---------------------------------------------------------------

    static Analysis analyze(Meal m) {
        return cache.computeIfAbsent(m.id + "|" + String.join(",", m.ingredients), k -> rules(m));
    }

    /** The hidden finding for one allergen in this meal, or null. */
    static Finding find(Meal m, String allergenKey) {
        for (Finding f : analyze(m).hidden) if (f.allergen.equals(allergenKey)) return f;
        return null;
    }

    static Set<String> hiddenKeys(Meal m) {
        Set<String> keys = new LinkedHashSet<>();
        for (Finding f : analyze(m).hidden) keys.add(f.allergen);
        return keys;
    }

    private static Analysis rules(Meal m) {
        Analysis a = new Analysis();
        List<String> direct = AllergenDb.allergensIn(m);           // already caught by plain word matching
        Set<String> seen = new HashSet<>();
        for (String ingredient : m.ingredients) {
            for (String[] row : HIDDEN) {
                if (!ingredient.contains(row[0])) continue;
                if (row[0].equals("soy sauce") && ingredient.contains("tamari")) continue;   // tamari is usually wheat-free
                if (direct.contains(row[1]) || !seen.add(row[1])) continue;
                a.hidden.add(new Finding(row[1], ingredient, row[2], "rules"));
            }
        }
        return a;
    }

    // ---------------------------------------------------------------
    //  Claude: reads each ingredient list and adds what the table missed
    // ---------------------------------------------------------------

    /** Analyses every item in the background so the first passenger never waits. */
    static void warmUp(List<Meal> items) {
        List<Meal> copy = new ArrayList<>(items);
        Thread t = new Thread(() -> {
            for (Meal m : copy) analyze(m);
            if (!Claude.enabled()) return;
            for (Meal m : copy) enrich(m);
        }, "allergen-analyst");
        t.setDaemon(true);
        t.start();
    }

    private static void enrich(Meal m) {
        Analysis a = analyze(m);
        if (a.source.equals("claude")) return;
        try {
            String system = "You are an airline catering allergen analyst. Given a meal's ingredient list, list allergens that are "
                + "PRESENT BUT HIDDEN inside sauces or compound ingredients (not simply named in the list). "
                + "Allowed allergen keys: " + String.join(",", AllergenDb.KEYWORDS.keySet()) + ". "
                + "Reply with ONE line: key:ingredient:short reason, items separated by ; (no colons inside the reason). "
                + "If nothing is hidden reply exactly NONE.";
            String reply = Claude.ask(system, "Meal: " + m.name + "\nIngredients: " + String.join(", ", m.ingredients)).trim();
            List<String> direct = AllergenDb.allergensIn(m);
            if (!reply.equalsIgnoreCase("NONE")) {
                for (String item : reply.split(";")) {
                    String[] p = item.trim().split(":", 3);
                    if (p.length < 3) continue;
                    String key = p[0].trim().toLowerCase();
                    if (!AllergenDb.KEYWORDS.containsKey(key) || direct.contains(key)) continue;
                    boolean known = false;
                    for (Finding f : a.hidden) if (f.allergen.equals(key)) known = true;
                    if (known) continue;
                    a.hidden.add(new Finding(key, clip(p[1].trim(), 40), clip(p[2].trim(), 120), "claude"));
                }
            }
            a.source = "claude";
        } catch (Exception e) {
            System.out.println("Claude allergen analysis failed for " + m.name + ": " + e.getMessage());
        }
    }

    private static String clip(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
}
