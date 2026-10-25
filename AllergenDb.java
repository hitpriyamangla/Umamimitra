package com.umamimitra.galley;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Our allergen knowledge: which ingredient words belong to which allergen.
 * Covers common Indian-meal allergens (dairy, mustard, coconut, gluten...) and the ones that
 * matter for Japanese food (buckwheat/soba, shrimp and crab, soy, sesame, fish, walnut...).
 * Meals store only their INGREDIENTS. Allergens are worked out from the ingredients.
 */
public class AllergenDb {

    static final Map<String, String[]> KEYWORDS = new LinkedHashMap<>();
    static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        add("peanuts",   "Peanuts",           "peanut");
        add("treenuts",  "Tree nuts",         "cashew", "almond", "walnut", "hazelnut", "pistachio", "pecan", "macadamia");
        add("dairy",     "Milk / dairy",      "milk", "cream", "butter", "ghee", "paneer", "cheese", "parmesan", "yogurt", "curd");
        add("egg",       "Egg",               "egg", "mayonnaise");
        add("gluten",    "Wheat / gluten",    "wheat", "pasta", "panko", "breadcrumb", "barley", "rye");
        add("soy",       "Soy",               "soy", "tofu", "miso", "tamari");
        add("sesame",    "Sesame",            "sesame", "tahini");
        add("fish",      "Fish",              "fish", "salmon", "bonito", "anchovy", "tuna");
        add("shellfish", "Shrimp / crab",     "shrimp", "prawn", "crab", "shellfish", "lobster");
        add("buckwheat", "Buckwheat / soba",  "buckwheat", "soba");
        add("mustard",   "Mustard",           "mustard");
        add("coconut",   "Coconut",           "coconut");
    }

    private static void add(String key, String label, String... words) {
        KEYWORDS.put(key, words);
        LABELS.put(key, label);
    }

    /**
     * Looks through a meal's ingredients for any of the keywords.
     * Whole-word match, so "eggplant" does NOT count as "egg".
     * @return the ingredient that matched, or null if the meal is clear
     */
    static String match(Meal meal, Collection<String> keywords) {
        for (String ingredient : meal.ingredients) {
            for (String word : keywords) {
                if (Pattern.compile("\\b" + Pattern.quote(word) + "s?\\b").matcher(ingredient).find()) {
                    return ingredient;
                }
            }
        }
        return null;
    }

    /** Which of our standard allergens does this meal contain? (shown to the passenger) */
    static List<String> allergensIn(Meal meal) {
        List<String> found = new ArrayList<>();
        for (String key : KEYWORDS.keySet()) {
            if (match(meal, Arrays.asList(KEYWORDS.get(key))) != null) found.add(key);
        }
        return found;
    }

    /** If a typed word is one of our standard allergens, return its key (else null). */
    static String keyFor(String term) {
        String t = term.toLowerCase().trim();
        if (t.endsWith("s") && t.length() > 3) t = t.substring(0, t.length() - 1);
        for (String key : KEYWORDS.keySet()) {
            if (key.equals(t) || LABELS.get(key).toLowerCase().equals(t)) return key;
            for (String w : KEYWORDS.get(key)) if (w.equals(t)) return key;
        }
        return null;
    }
}
