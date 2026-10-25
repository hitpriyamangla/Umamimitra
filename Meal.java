package com.umamimitra.galley;

import java.util.ArrayList;
import java.util.List;

/**
 * One item on the flight: either a full MEAL, or a galley COMPONENT
 * (MAIN, CARB or SIDE) used to assemble a custom safe plate.
 */
public class Meal {

    public enum Type { JAIN, VEGAN, VEGETARIAN, NON_VEG }

    String id, name, description;
    String kind = "MEAL";          // MEAL, MAIN, CARB, SIDE
    Type type;
    int spice;                     // 0 mild, 1 medium, 2 spicy
    boolean hasBeef, hasPork;
    boolean jainSafe;              // components only: fine for Jain passengers
    int start, stock;
    double cost;                   // mock cost per portion (USD)
    int kcal, protein, carbs, fat, fibre, sodium;   // estimated per portion: kcal, grams, grams, grams, grams, mg
    List<String> ingredients = new ArrayList<>();

    Meal(String id, String name, String description, Type type, int spice,
         boolean hasBeef, boolean hasPork, int stock, double cost, String ingredientList) {
        this.id = id; this.name = name; this.description = description;
        this.type = type; this.spice = spice;
        this.hasBeef = hasBeef; this.hasPork = hasPork;
        this.start = stock; this.stock = stock; this.cost = cost;
        for (String s : ingredientList.split(",")) ingredients.add(s.trim().toLowerCase());
    }

    /** A galley component for custom plates. */
    static Meal component(String id, String name, String kind, Type type, boolean jainSafe,
                          int stock, double cost, String ingredientList) {
        Meal m = new Meal(id, name, "", type, 0, false, false, stock, cost, ingredientList);
        m.kind = kind;
        m.jainSafe = jainSafe;
        return m;
    }

    /** Sets the estimated nutrition for one portion (mock data; a real build uses the caterer's recipe data). */
    Meal nutrition(int kcal, int protein, int carbs, int fat, int fibre, int sodium) {
        this.kcal = kcal; this.protein = protein; this.carbs = carbs; this.fat = fat; this.fibre = fibre; this.sodium = sodium;
        return this;
    }

    /** Kinds of meat in this item (beef, pork, chicken, fish). Empty for vegetarian food. */
    List<String> meatKinds() {
        List<String> k = new ArrayList<>();
        if (hasBeef) k.add("beef");
        if (hasPork) k.add("pork");
        boolean chicken = false, fish = false;
        for (String i : ingredients) {
            if (i.contains("chicken") || i.contains("mutton") || i.contains("lamb")) chicken = true;
            if (i.contains("salmon") || i.contains("fish") || i.contains("tuna") || i.contains("anchovy")
                || i.contains("prawn") || i.contains("shrimp") || i.contains("crab")) fish = true;
        }
        if (chicken) k.add("chicken");
        if (fish) k.add("fish");
        return k;
    }

    /** Lower-case text used to match a passenger's food preferences ("chicken", "paneer", "fish"...). */
    String searchText() {
        return (name + " " + description + " " + String.join(" ", ingredients) + " " + String.join(" ", meatKinds())).toLowerCase();
    }

    String nutritionJson() {
        return "{\"kcal\":" + kcal + ",\"protein\":" + protein + ",\"carbs\":" + carbs
             + ",\"fat\":" + fat + ",\"fibre\":" + fibre + ",\"sodium\":" + sodium + "}";
    }

    String toJson(boolean spiceMatch) { return toJson(spiceMatch, true); }

    /** @param primary true when the item fits the passenger's main food preference (diet group), false for "other options" */
    String toJson(boolean spiceMatch, boolean primary) {
        return "{\"id\":" + Json.quote(id)
             + ",\"name\":" + Json.quote(name)
             + ",\"description\":" + Json.quote(description)
             + ",\"kind\":" + Json.quote(kind)
             + ",\"type\":" + Json.quote(type.name())
             + ",\"spice\":" + spice
             + ",\"start\":" + start
             + ",\"stock\":" + stock
             + ",\"cost\":" + cost
             + ",\"spiceMatch\":" + spiceMatch
             + ",\"primary\":" + primary
             + ",\"nutrition\":" + nutritionJson()
             + ",\"meat\":" + Json.array(meatKinds())
             + ",\"allergens\":" + Json.array(allAllergens())
             + ",\"hidden\":" + hiddenJson()
             + ",\"ingredients\":" + Json.array(ingredients) + "}";
    }

    /** Allergens named in the ingredients, plus the hidden ones the AI analyst found. */
    List<String> allAllergens() {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(AllergenDb.allergensIn(this));
        all.addAll(MealAnalyzer.hiddenKeys(this));
        return new ArrayList<>(all);
    }

    String hiddenJson() {
        StringBuilder sb = new StringBuilder("[");
        for (MealAnalyzer.Finding f : MealAnalyzer.analyze(this).hidden) {
            if (sb.length() > 1) sb.append(",");
            sb.append(f.toJson());
        }
        return sb.append("]").toString();
    }
}
