package com.umamimitra.galley;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything a passenger told us: diet, beef/pork, allergies, food likes, spice. Also the body of a Dietary Passport. */
public class Restrictions {
    String diet = "vegetarian";           // vegetarian, vegan, jain, nonveg
    boolean noBeef = false;
    boolean noPork = false;
    List<String> allergies = new ArrayList<>();                    // standard keys, e.g. "peanuts"
    Map<String, List<String>> custom = new LinkedHashMap<>();      // typed allergy -> ingredient words to avoid
    boolean severe = false;               // passenger says the allergy is severe
    String spice = "mild";                // low, mild, spicy   (a SECONDARY preference: it only orders meals)
    List<String> likes = new ArrayList<>();                        // foods the passenger prefers (PRIMARY ordering after diet)

    /** Vegetarian, vegan and Jain passengers eat no meat of any kind. */
    boolean isVeg() { return diet.equals("vegetarian") || diet.equals("vegan") || diet.equals("jain"); }

    /**
     * Makes the choices consistent. Called on every path (web, Bluetooth, passport):
     *  - a veg diet always means no beef AND no pork (and no chicken or fish, enforced in MealService),
     *  - the old spice value "any" becomes "mild", unknown values become "mild".
     */
    Restrictions normalize() {
        if (isVeg()) { noBeef = true; noPork = true; }
        if (spice == null || !(spice.equals("low") || spice.equals("mild") || spice.equals("spicy"))) spice = "mild";
        return this;
    }

    Restrictions copy() {
        Restrictions c = new Restrictions();
        c.diet = diet; c.noBeef = noBeef; c.noPork = noPork; c.severe = severe; c.spice = spice;
        c.allergies = new ArrayList<>(allergies);
        c.likes = new ArrayList<>(likes);
        for (Map.Entry<String, List<String>> e : custom.entrySet()) c.custom.put(e.getKey(), new ArrayList<>(e.getValue()));
        return c;
    }

    String toJson() {
        return "{\"diet\":" + Json.quote(diet)
             + ",\"noBeef\":" + noBeef
             + ",\"noPork\":" + noPork
             + ",\"allergies\":" + Json.array(allergies)
             + ",\"custom\":" + Json.array(new ArrayList<>(custom.keySet()))
             + ",\"severe\":" + severe
             + ",\"spice\":" + Json.quote(spice)
             + ",\"likes\":" + Json.array(likes) + "}";
    }
}
