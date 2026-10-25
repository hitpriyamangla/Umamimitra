package com.umamimitra.galley;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles allergies that are NOT in our standard list (kiwi, nightshades, latex-fruit...).
 * It turns the passenger's words into a list of ingredient words to watch for.
 *   With a Claude key: Claude suggests the related ingredients.
 *   Without a key:     a small built-in table of common groups is used.
 * Answers are cached, so each allergy is looked up only once.
 */
public class AllergenExpander {

    public static class Expansion {
        List<String> keywords = new ArrayList<>();
        String source = "rules";
    }

    private static final Map<String, Expansion> cache = new ConcurrentHashMap<>();
    private static final Map<String, String[]> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("nightshade", new String[]{"tomato", "potato", "eggplant", "chili", "capsicum", "pepper"});
        GROUPS.put("legume",     new String[]{"lentil", "chickpea", "bean", "pea", "soy", "tofu", "peanut", "dal"});
        GROUPS.put("pulse",      new String[]{"lentil", "chickpea", "bean", "pea", "dal"});
        GROUPS.put("citrus",     new String[]{"lemon", "lime", "orange", "yuzu"});
        GROUPS.put("allium",     new String[]{"onion", "garlic", "leek", "scallion"});
        GROUPS.put("mushroom",   new String[]{"mushroom", "shiitake"});
        GROUPS.put("stone fruit",new String[]{"peach", "plum", "cherry", "apricot"});
        GROUPS.put("latex",      new String[]{"avocado", "banana", "kiwi", "chestnut"});
        GROUPS.put("seaweed",    new String[]{"nori", "kombu", "seaweed", "wakame"});
    }

    static Expansion expand(String text) {
        String key = text.trim().toLowerCase();
        return cache.computeIfAbsent(key, AllergenExpander::compute);
    }

    private static Expansion compute(String term) {
        if (Claude.enabled()) {
            try {
                String reply = Claude.ask(
                    "A passenger has a food allergy or intolerance. List the ingredient words an airline caterer "
                  + "must avoid for it. Reply with ONE line only: lowercase words separated by commas, max 15, no explanation.",
                    term);
                Expansion e = new Expansion();
                e.source = "claude";
                for (String w : reply.split("[,\n]")) {
                    w = w.trim().toLowerCase();
                    if (w.matches("[a-z][a-z \\-]{1,28}") && e.keywords.size() < 15) e.keywords.add(w);
                }
                if (!e.keywords.contains(term)) e.keywords.add(0, term);
                if (e.keywords.size() > 1) return e;
            } catch (Exception ex) {
                System.out.println("Claude call failed, using built-in table: " + ex.getMessage());
            }
        }
        return rules(term);
    }

    private static Expansion rules(String term) {
        Expansion e = new Expansion();
        e.keywords.add(term);
        if (term.endsWith("s") && term.length() > 3) e.keywords.add(term.substring(0, term.length() - 1));

        // A known group? (e.g. "nightshades" -> tomato, potato...)
        for (Map.Entry<String, String[]> g : GROUPS.entrySet()) {
            if (term.contains(g.getKey())) e.keywords.addAll(Arrays.asList(g.getValue()));
        }
        // A known allergen written another way? (e.g. "lactose" or "curd" -> all dairy words)
        String key = AllergenDb.keyFor(term);
        if (key != null) e.keywords.addAll(Arrays.asList(AllergenDb.KEYWORDS.get(key)));
        if (term.contains("lactose")) e.keywords.addAll(Arrays.asList(AllergenDb.KEYWORDS.get("dairy")));
        return e;
    }
}
