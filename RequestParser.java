package com.umamimitra.galley;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "AI interpreter": turns a sentence like
 *   "I'm Hindu, no onion or garlic, allergic to peanuts and kiwi, I like spicy food"
 * into structured choices (diet, beef/pork, allergies, spice).
 *
 * With ANTHROPIC_API_KEY set, Claude reads it. Otherwise keyword rules do. The demo never breaks.
 */
public class RequestParser {

    public static class Parsed {
        String diet = "vegetarian";
        boolean noBeef = false;
        boolean noPork = false;
        List<String> allergies = new ArrayList<>();   // standard allergens
        List<String> custom = new ArrayList<>();      // allergies we don't have a checkbox for
        boolean severe = false;
        String spice = "mild";
        List<String> likes = new ArrayList<>();       // foods the passenger prefers
        String source = "rules";

        String toJson() {
            return "{\"diet\":" + Json.quote(diet)
                 + ",\"noBeef\":" + noBeef
                 + ",\"noPork\":" + noPork
                 + ",\"allergies\":" + Json.array(allergies)
                 + ",\"custom\":" + Json.array(custom)
                 + ",\"severe\":" + severe
                 + ",\"spice\":" + Json.quote(spice)
                 + ",\"likes\":" + Json.array(likes)
                 + ",\"source\":" + Json.quote(source) + "}";
        }
    }

    public static Parsed parse(String text) {
        if (Claude.enabled()) {
            try {
                return askClaude(text);
            } catch (Exception e) {
                System.out.println("Claude call failed, using rules instead: " + e.getMessage());
            }
        }
        return parseWithRules(text);
    }

    // ---------------- Option 1: keyword rules ----------------

    static Parsed parseWithRules(String text) {
        String t = text.toLowerCase();
        Parsed p = new Parsed();

        if (t.contains("jain")) p.diet = "jain";
        else if (t.contains("vegan")) p.diet = "vegan";
        else if (t.matches(".*\\b(vegetarian|veg)\\b.*") && !t.matches(".*non[- ]?veg.*")) p.diet = "vegetarian";
        else p.diet = "nonveg";

        p.noBeef = t.contains("beef") || t.contains("hindu");
        p.noPork = t.contains("pork") || t.contains("muslim") || t.contains("halal");
        // Vegetarian, vegan and Jain mean no meat at all: beef, pork, chicken and fish are all out.
        if (!p.diet.equals("nonveg")) { p.noBeef = true; p.noPork = true; }

        if (t.contains("allerg")) {
            // Allergens we have a checkbox for
            if (t.contains("peanut") || t.contains(" nut")) p.allergies.add("peanuts");
            if (t.contains("tree nut") || t.contains("almond") || t.contains("cashew")
                    || t.contains("walnut") || t.contains(" nuts")) p.allergies.add("treenuts");
            if (t.contains("dairy") || t.contains("milk") || t.contains("lactose")) p.allergies.add("dairy");
            if (t.contains("egg")) p.allergies.add("egg");
            if (t.contains("gluten") || t.contains("wheat")) p.allergies.add("gluten");
            if (t.contains("soy")) p.allergies.add("soy");
            if (t.contains("sesame")) p.allergies.add("sesame");
            if (t.contains("fish") || t.contains("salmon")) p.allergies.add("fish");
            if (t.contains("shrimp") || t.contains("prawn") || t.contains("crab") || t.contains("shellfish")) p.allergies.add("shellfish");
            if (t.contains("buckwheat") || t.contains("soba")) p.allergies.add("buckwheat");
            if (t.contains("mustard")) p.allergies.add("mustard");
            if (t.contains("coconut")) p.allergies.add("coconut");

            // Anything else after "allergic to ..." goes to the custom list
            Matcher m = Pattern.compile("(?:allergic to|allergy to|allergies to|allergies:|allergy:)\\s*([^.;]*)").matcher(t);
            if (m.find()) {
                String chunk = m.group(1).split(" but | i like | and i |, i ")[0];
                for (String term : chunk.split(",| and | & ")) {
                    term = term.trim().replaceAll("^(the|any|all|of)\\s+", "");
                    if (term.isEmpty() || term.split(" ").length > 3) continue;
                    if (AllergenDb.keyFor(term) == null && !term.matches(".*\\b(nut|nuts)\\b.*")
                            && p.custom.size() < 5 && !p.custom.contains(term)) {
                        p.custom.add(term);
                    }
                }
            }
        }

        p.severe = isSevere(t);

        if (t.matches(".*(not (too|very|so|that|overly) spic|less spic|a bit spic|slightly spic|little spic).*")) p.spice = "mild";
        else if (t.contains("not spicy") || t.contains("no spice") || t.contains("low spice") || t.matches(".*\\blow\\b.*")) p.spice = "low";
        else if (t.contains("mild")) p.spice = "mild";
        else if (t.contains("spic") || t.contains("chili") || t.contains("chilli") || t.matches(".*\\bhot\\b.*")) p.spice = "spicy";

        p.likes = likesIn(t);
        return p;
    }

    /** Foods the passenger says they like or prefer: "I prefer chicken and fish" -> [chicken, fish]. */
    static List<String> likesIn(String t) {
        List<String> likes = new ArrayList<>();
        Matcher m = Pattern.compile("(?:i (?:really )?(?:like|love|prefer|want|crave)|prefer(?:ably)?|favou?rite is)\\s+([^.;]*)").matcher(t);
        while (m.find()) {
            for (String term : m.group(1).split(",| and | & | but | with ")) {
                term = term.trim().replaceAll("^(to eat|to have|eating|some|the|a|an|my)\\s+", "");
                if (term.isEmpty() || term.split(" ").length > 2) continue;
                if (term.matches(".*\\b(spic\\w*|mild|hot|food|foods|meal|meals|low|less)\\b.*")) continue;   // spice words are not foods
                if (likes.size() < 3 && !likes.contains(term)) likes.add(term);
            }
        }
        return likes;
    }

    // ---------------- Option 2: ask Claude ----------------

    private static Parsed askClaude(String text) throws Exception {
        String system = "You read an airline passenger's meal request. Reply with EXACTLY these 7 lines and nothing else:\n"
            + "diet=vegetarian|vegan|jain|nonveg\n"
            + "noBeef=true|false\n"
            + "noPork=true|false\n"
            + "allergies=comma list from peanuts,treenuts,dairy,egg,gluten,soy,sesame,fish,shellfish,buckwheat,mustard,coconut (or empty)\n"
            + "custom=comma list of any OTHER allergies or intolerances mentioned (or empty)\n"
            + "spice=low|mild|spicy\n"
            + "likes=comma list of foods the passenger says they prefer, max 3 (or empty)";
        String reply = Claude.ask(system, text);

        Parsed p = new Parsed();
        p.source = "claude";
        for (String line : reply.split("\n")) {
            String[] kv = line.split("=", 2);
            if (kv.length < 2) continue;
            String value = kv[1].trim().toLowerCase();
            switch (kv[0].trim()) {
                case "diet":
                    if (List.of("vegetarian", "vegan", "jain", "nonveg").contains(value)) p.diet = value;
                    break;
                case "noBeef": p.noBeef = value.equals("true"); break;
                case "noPork": p.noPork = value.equals("true"); break;
                case "spice":
                    if (List.of("low", "mild", "spicy").contains(value)) p.spice = value;
                    break;
                case "likes":
                    for (String l : value.split(",")) {
                        if (!l.isBlank() && p.likes.size() < 3) p.likes.add(l.trim());
                    }
                    break;
                case "allergies":
                    for (String a : value.split(",")) {
                        if (AllergenDb.KEYWORDS.containsKey(a.trim())) p.allergies.add(a.trim());
                    }
                    break;
                case "custom":
                    for (String c : value.split(",")) {
                        if (!c.isBlank() && p.custom.size() < 5) p.custom.add(c.trim());
                    }
                    break;
            }
        }
        p.severe = isSevere(text.toLowerCase());
        if (!p.diet.equals("nonveg")) { p.noBeef = true; p.noPork = true; }       // no meat of any kind
        return p;
    }

    /** Words that mean the passenger says the allergy is serious. */
    static boolean isSevere(String t) {
        return t.matches(".*(severe|anaphyla|epipen|life[- ]threatening|serious reaction).*");
    }
}
