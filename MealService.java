package com.umamimitra.galley;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * The "brain" of UmamiMitra.
 * It owns the menu, the galley components, the requests, the safety rules,
 * the custom-plate builder and the waste planner.
 * Methods that change data are "synchronized" so several screens can't clash.
 */
public class MealService {

    private final List<Meal> menu = new ArrayList<>();
    private final List<Meal> components = new ArrayList<>();
    private final List<Request> requests = new ArrayList<>();
    private final Map<String, Passport> passports = new LinkedHashMap<>();   // Dietary Passports, key = login id (lower case)
    private int nextId = 1;
    private Map<String, Integer> approvedPlan = null;    // next-flight load approved by catering
    private String approvedAt = "";

    public static class Removed {
        Meal meal; String reason;
        Removed(Meal meal, String reason) { this.meal = meal; this.reason = reason; }
    }

    public static class SearchResult {
        List<Meal> available = new ArrayList<>();
        List<Removed> removed = new ArrayList<>();
        List<Meal> plate = null;      // custom safe plate, only built when no whole meal is safe
        boolean plateTried = false;
    }

    public MealService() {
        loadMenu();
        List<Meal> all = new ArrayList<>(menu);
        all.addAll(components);
        MealAnalyzer.warmUp(all);          // AI reads every ingredient list in the background
    }

    // ---------------------------------------------------------------
    //  Mock flight data: JL740 Delhi -> Narita
    //  Meal(id, name, description, type, spice, hasBeef, hasPork, stock, cost, ingredients)
    // ---------------------------------------------------------------
    private void loadMenu() {
        menu.clear();
        components.clear();

        // Indian meals
        menu.add(new Meal("thali", "Veg Thali", "Dal, paneer, rice and roti", Meal.Type.VEGETARIAN, 1, false, false, 30, 5.0,
                "wheat flour,lentils,paneer,ghee,rice,potato,peas,tomato,onion,garlic,cumin,mustard seeds,peanut chutney"));
        menu.add(new Meal("paneer", "Spicy Paneer Curry", "Paneer in a hot tomato gravy", Meal.Type.VEGETARIAN, 2, false, false, 16, 6.0,
                "paneer,cream,cashew,tomato,onion,garlic,ginger,chili,butter,basmati rice"));
        menu.add(new Meal("pasta", "Veg Pasta", "Penne in creamy tomato sauce", Meal.Type.VEGETARIAN, 0, false, false, 10, 5.0,
                "wheat pasta,cream,parmesan cheese,egg,tomato,basil,olive oil"));
        menu.add(new Meal("jain", "Jain Meal", "No onion, garlic or root vegetables", Meal.Type.JAIN, 1, false, false, 6, 6.0,
                "wheat flour,lentils,bottle gourd,rice,cumin,asafoetida,vegetable oil"));
        menu.add(new Meal("vegan", "Vegan Bowl", "Rice, tofu and vegetables", Meal.Type.VEGAN, 1, false, false, 12, 5.0,
                "rice,tofu,tamari soy sauce,broccoli,carrot,sesame seeds,peanut sauce"));
        menu.add(new Meal("chicken", "Chicken Curry Meal", "Chicken curry with rice", Meal.Type.NON_VEG, 2, false, false, 24, 6.0,
                "chicken,yogurt,onion,garlic,tomato,coconut,mustard seeds,basmati rice"));

        // Japanese meals
        menu.add(new Meal("salmon", "Salmon Teriyaki", "Grilled salmon with rice", Meal.Type.NON_VEG, 0, false, false, 20, 8.0,
                "salmon,soy sauce,wheat,mirin,sugar,rice,sesame seeds,broccoli"));
        menu.add(new Meal("gyudon", "Gyudon (Japanese Beef Bowl)", "Beef and onion over rice", Meal.Type.NON_VEG, 0, true, false, 22, 7.0,
                "beef,onion,soy sauce,wheat,mirin,bonito dashi,rice,ginger"));
        menu.add(new Meal("tonkatsu", "Tonkatsu (Pork Cutlet)", "Breaded pork cutlet with cabbage", Meal.Type.NON_VEG, 0, false, true, 14, 7.0,
                "pork,wheat flour,panko breadcrumbs,egg,soy sauce,wheat,cabbage,sesame seeds,tonkatsu sauce"));
        menu.add(new Meal("maki", "Vegetable Maki Set", "Sushi rolls with cucumber and avocado", Meal.Type.VEGAN, 0, false, false, 12, 6.0,
                "sushi rice,rice vinegar,nori seaweed,cucumber,avocado,carrot,sesame seeds,soy sauce,wheat,wasabi"));
        menu.add(new Meal("soba", "Cold Vegetable Soba", "Buckwheat noodles with dipping sauce", Meal.Type.VEGAN, 0, false, false, 10, 6.0,
                "buckwheat noodles,wheat,soy sauce,kombu dashi,mirin,scallion,daikon radish,sesame seeds"));

        // Galley components: plain, sealed, pre-portioned. Used to build a custom safe plate.
        // component(id, name, kind, type, jainSafe, stock, cost, ingredients)
        components.add(Meal.component("c_chicken", "Grilled chicken (plain)", "MAIN", Meal.Type.NON_VEG, false, 30, 3.0, "chicken,salt,olive oil"));
        components.add(Meal.component("c_salmon", "Steamed salmon (plain)", "MAIN", Meal.Type.NON_VEG, false, 16, 4.0, "salmon,salt"));
        components.add(Meal.component("c_egg", "Boiled eggs", "MAIN", Meal.Type.NON_VEG, false, 16, 1.5, "egg,salt"));
        components.add(Meal.component("c_paneer", "Grilled paneer (plain)", "MAIN", Meal.Type.VEGETARIAN, true, 14, 3.0, "paneer,salt,olive oil"));
        components.add(Meal.component("c_lentils", "Boiled lentils (plain)", "MAIN", Meal.Type.VEGAN, true, 30, 1.5, "lentils,salt,cumin"));
        components.add(Meal.component("c_tofu", "Steamed tofu (plain)", "MAIN", Meal.Type.VEGAN, false, 14, 2.0, "tofu,salt"));
        components.add(Meal.component("c_rice", "Steamed rice", "CARB", Meal.Type.VEGAN, true, 60, 1.0, "rice"));
        components.add(Meal.component("c_roti", "Plain roti", "CARB", Meal.Type.VEGAN, true, 40, 1.0, "wheat flour,water"));
        components.add(Meal.component("c_potato", "Boiled potatoes", "CARB", Meal.Type.VEGAN, false, 30, 1.0, "potato,salt"));
        components.add(Meal.component("c_veg", "Steamed vegetables", "SIDE", Meal.Type.VEGAN, false, 40, 1.5, "broccoli,carrot,zucchini"));
        components.add(Meal.component("c_fruit", "Fresh fruit cup", "SIDE", Meal.Type.VEGAN, true, 40, 1.5, "melon,grapes,pineapple"));
        components.add(Meal.component("c_salad", "Cucumber salad", "SIDE", Meal.Type.VEGAN, true, 30, 1.0, "cucumber,olive oil,lemon"));

        // Estimated nutrition per portion (mock): kcal, protein g, carbs g, fat g, fibre g, sodium mg
        nut("thali", 640, 21, 92, 20, 12, 820);   nut("paneer", 690, 27, 60, 37, 6, 900);
        nut("pasta", 590, 19, 74, 24, 5, 700);    nut("jain", 520, 18, 84, 11, 11, 600);
        nut("vegan", 480, 20, 62, 17, 9, 760);    nut("chicken", 620, 38, 62, 24, 4, 880);
        nut("salmon", 560, 34, 58, 20, 4, 1050);  nut("gyudon", 650, 28, 82, 21, 3, 1100);
        nut("tonkatsu", 720, 32, 66, 36, 5, 980); nut("maki", 420, 11, 78, 7, 6, 820);
        nut("soba", 380, 14, 70, 4, 5, 900);
        nut("c_chicken", 190, 35, 0, 5, 0, 120);  nut("c_salmon", 210, 28, 0, 10, 0, 90);
        nut("c_egg", 155, 13, 1, 11, 0, 125);     nut("c_paneer", 260, 18, 3, 20, 0, 180);
        nut("c_lentils", 200, 14, 34, 1, 8, 200); nut("c_tofu", 140, 15, 3, 8, 2, 20);
        nut("c_rice", 200, 4, 44, 0, 1, 5);       nut("c_roti", 120, 4, 23, 2, 3, 150);
        nut("c_potato", 160, 4, 36, 0, 3, 150);   nut("c_veg", 60, 4, 12, 1, 5, 40);
        nut("c_fruit", 80, 1, 20, 0, 2, 5);       nut("c_salad", 45, 2, 6, 3, 1, 10);
    }

    private void nut(String id, int kcal, int protein, int carbs, int fat, int fibre, int sodium) {
        for (Meal m : menu) if (m.id.equals(id)) m.nutrition(kcal, protein, carbs, fat, fibre, sodium);
        for (Meal m : components) if (m.id.equals(id)) m.nutrition(kcal, protein, carbs, fat, fibre, sodium);
    }

    // ---------------------------------------------------------------
    //  The safety rules
    // ---------------------------------------------------------------

    /** @return why this item is NOT ok for the passenger, or null if it is fine. */
    private String whyNot(Meal m, Restrictions r) { return whyNot(m, r, true); }

    private String whyNot(Meal m, Restrictions r, boolean checkStock) {
        if (r.noBeef && m.hasBeef) return "contains beef";
        if (r.noPork && m.hasPork) return "contains pork";
        if (checkStock && m.stock <= 0) return "out of stock";

        // Standard allergies: check every ingredient against the allergen word list
        for (String key : r.allergies) {
            String[] words = AllergenDb.KEYWORDS.get(key);
            if (words == null) continue;
            String ingredient = AllergenDb.match(m, Arrays.asList(words));
            if (ingredient != null) {
                return "contains " + AllergenDb.LABELS.get(key).toLowerCase() + " (" + ingredient + ")";
            }
            // Hidden allergen found by the AI analyst (sauces, compound ingredients). It can only ADD a warning.
            MealAnalyzer.Finding f = MealAnalyzer.find(m, key);
            if (f != null) {
                return "AI flagged " + AllergenDb.LABELS.get(key).toLowerCase() + " in " + f.ingredient + " (" + f.why + ")";
            }
        }
        // Allergies the passenger typed: same check, with the words the AI expanded them into
        for (Map.Entry<String, List<String>> c : r.custom.entrySet()) {
            String ingredient = AllergenDb.match(m, c.getValue());
            if (ingredient != null) {
                return "contains your allergen \"" + c.getKey() + "\" (" + ingredient + ")";
            }
        }
        return null;
    }

    private boolean matchesDiet(Meal m, String diet) {
        switch (diet) {
            case "jain":       return m.type == Meal.Type.JAIN || (!m.kind.equals("MEAL") && m.jainSafe);
            case "vegan":      return m.type == Meal.Type.VEGAN;
            case "vegetarian": return m.type != Meal.Type.NON_VEG;
            default:           return true;
        }
    }

    /** Spice is only a wish about HOW hot the food is. low = 0, mild = 1, spicy = 2 (pepper icons on the menu). */
    public boolean spiceMatches(Meal m, String spice) {
        if (spice.equals("spicy")) return m.spice >= 2;
        if (spice.equals("low"))   return m.spice == 0;
        return m.spice == 1;                                  // mild
    }

    /** How far this meal is from the wished spice level (0 = perfect). Used only as a SECONDARY sort. */
    private double spiceDistance(Meal m, String spice) {
        int target = spice.equals("spicy") ? 2 : spice.equals("low") ? 0 : 1;
        return Math.abs(m.spice - target) + (m.spice > target ? 0.25 : 0);     // on a tie, the milder meal wins
    }

    /**
     * PRIMARY preference: does this meal fit the passenger's diet group?
     * Non-veg passengers: non-veg meals first, veg options only at the end.
     * Vegetarian: vegetarian meals first (vegan ones are fine but come after). Vegan and Jain: only their own group.
     */
    public boolean primaryMatch(Meal m, Restrictions r) {
        switch (r.diet) {
            case "nonveg":     return m.type == Meal.Type.NON_VEG;
            case "vegetarian": return m.type == Meal.Type.VEGETARIAN;
            case "vegan":      return m.type == Meal.Type.VEGAN;
            default:           return m.type == Meal.Type.JAIN;
        }
    }

    /** How many of the passenger's food likes ("chicken", "paneer") this meal fits. */
    private int likeScore(Meal m, Restrictions r) {
        String text = m.searchText();
        int n = 0;
        for (String like : r.likes) if (!like.isBlank() && text.contains(like.toLowerCase())) n++;
        return n;
    }

    /** Why a vegetarian, vegan or Jain passenger cannot have this meat meal, or null. */
    private String meatReason(Meal m, Restrictions r) {
        if (!r.isVeg() || m.type != Meal.Type.NON_VEG) return null;
        List<String> kinds = m.meatKinds();
        String what = kinds.isEmpty() ? "meat" : String.join(" and ", kinds);
        return "contains " + what + " (not " + r.diet + ")";
    }

    /** Which whole meals can this passenger have? If none, try to build a custom plate. */
    public synchronized SearchResult search(Restrictions r) {
        r.normalize();                                      // a veg diet always means no beef, pork, chicken or fish
        SearchResult result = new SearchResult();

        for (Meal m : menu) {
            String meat = meatReason(m, r);
            if (meat != null) { result.removed.add(new Removed(m, meat)); continue; }   // shown, so the passenger sees ALL meat gone
            if (!matchesDiet(m, r.diet)) continue;          // other diet group: skip silently
            String why = whyNot(m, r);
            if (why == null) result.available.add(m); else result.removed.add(new Removed(m, why));
        }

        // Order (most important first):
        //   1. the passenger's own food group     (non-veg first for a non-veg passenger)
        //   2. the foods they said they like
        //   3. spice level                         (secondary: it never lifts a meal out of its group)
        //   4. the meal with the MOST stock left   (reduces waste)
        result.available.sort(Comparator
                .comparing((Meal m) -> !primaryMatch(m, r))
                .thenComparing((Meal m) -> -likeScore(m, r))
                .thenComparing((Meal m) -> spiceDistance(m, r.spice))
                .thenComparing((a, b) -> Double.compare((double) b.stock / b.start, (double) a.stock / a.start)));

        if (result.available.isEmpty()) {
            result.plateTried = true;
            result.plate = buildPlate(r);
        }
        return result;
    }

    /** Custom plate for this passenger, ignoring whole meals (used by the forecaster). */
    public synchronized List<Meal> plateFor(Restrictions r) { r.normalize(); return buildPlate(r); }

    /**
     * How much safe food exists for a kind of passenger: {whole-meal portions, MAIN portions, CARB portions}.
     * "extras" are optional galley components we could add (used by the forecaster to test new stock).
     */
    public synchronized int[] probe(Restrictions r, List<Meal> extras) {
        r.normalize();
        int whole = 0, main = 0, carb = 0;
        for (Meal m : menu) {
            if (meatReason(m, r) == null && matchesDiet(m, r.diet) && whyNot(m, r) == null) whole += m.stock;
        }
        List<Meal> all = new ArrayList<>(components);
        if (extras != null) all.addAll(extras);
        for (Meal c : all) {
            if (!matchesDiet(c, r.diet) || whyNot(c, r) != null) continue;
            if (c.kind.equals("MAIN")) main += c.stock;
            if (c.kind.equals("CARB")) carb += c.stock;
        }
        return new int[]{whole, main, carb};
    }

    /**
     * Custom safe plate: one MAIN + one CARB (+ one SIDE) picked from sealed galley components.
     * Every component goes through the same safety rules as a whole meal.
     * @return the components, or null if no safe plate can be made
     */
    private List<Meal> buildPlate(Restrictions r) {
        List<Meal> plate = new ArrayList<>();
        for (String kind : new String[]{"MAIN", "CARB", "SIDE"}) {
            Meal pick = null;
            for (Meal c : components) {                       // among safe components pick the best stocked one (spreads use, less waste)
                if (c.kind.equals(kind) && matchesDiet(c, r.diet) && whyNot(c, r) == null
                        && (pick == null || (double) c.stock / c.start > (double) pick.stock / pick.start)) pick = c;
            }
            if (pick != null) plate.add(pick);
            else if (!kind.equals("SIDE")) return null;       // a plate needs at least a main and a carb
        }
        return plate;
    }

    // ---------------------------------------------------------------
    //  Reserving
    // ---------------------------------------------------------------

    /**
     * @param mealId a meal id, "PLATE" (custom safe plate) or "CREW" (crew arranges something)
     * @return the new Request, or null if it is not allowed
     */
    public synchronized Request reserve(String mealId, String seat, Restrictions r) {
        SearchResult safe = search(r);                       // re-check: never trust the page

        if (mealId.equals("PLATE")) {
            if (safe.plate == null) return null;
            StringBuilder names = new StringBuilder();
            int kcal = 0;
            for (Meal c : safe.plate) {
                c.stock--;
                kcal += c.kcal;
                if (names.length() > 0) names.append(" + ");
                names.append(c.name);
            }
            Request q = newRequest(seat, r, "Custom safe plate", "PLATE");
            q.components = names.toString();
            q.kcal = kcal;
            return q;
        }
        if (mealId.equals("CREW")) {
            if (!safe.available.isEmpty() || safe.plate != null) return null;   // only when nothing else works
            return newRequest(seat, r, "No safe meal found: crew to arrange", "CREW");
        }
        for (Meal m : safe.available) {
            if (m.id.equals(mealId)) {
                m.stock--;
                Request q = newRequest(seat, r, m.name, "MEAL");
                q.kcal = m.kcal;
                return q;
            }
        }
        return null;
    }

    private Request newRequest(String seat, Restrictions r, String mealName, String kind) {
        Request q = new Request();
        q.id = nextId++;
        q.seat = seat;
        q.mealName = mealName;
        q.kind = kind;
        q.diet = r.diet;
        q.noBeef = r.noBeef;
        q.noPork = r.noPork;
        q.allergies = new ArrayList<>(r.allergies);
        q.custom = new ArrayList<>(r.custom.keySet());
        q.spice = r.spice;
        q.severe = r.severe;
        q.time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        requests.add(q);
        return q;
    }

    // ---------------------------------------------------------------
    //  In-flight changes (arrive over Bluetooth)
    // ---------------------------------------------------------------

    public static class Amendment {
        public String outcome = "";               // KEPT, CHANGED, NEW, TOO_LATE
        public Request request;
        public String previous = "";
        public String why = "";
        public List<Removed> removed = new ArrayList<>();
    }

    /**
     * A passenger changed diet or allergies during the flight.
     *   - meal still safe          -> keep it, update the request, crew re-checks
     *   - meal no longer safe      -> give its stock back, pick the best safe replacement
     *   - no request yet           -> book the best safe option
     *   - already served           -> too late, tell the passenger to speak to the crew
     */
    public synchronized Amendment amend(String seat, Restrictions r) {
        Amendment a = new Amendment();
        r.normalize();
        SearchResult sr = search(r);
        a.removed = sr.removed;
        String bestId = !sr.available.isEmpty() ? sr.available.get(0).id : sr.plate != null ? "PLATE" : "CREW";

        Request cur = null;
        boolean served = false;
        for (int i = requests.size() - 1; i >= 0; i--) {         // only the passenger's latest request matters
            Request q = requests.get(i);
            if (!q.seat.equalsIgnoreCase(seat)) continue;
            if (q.status.equals("SERVED")) served = true; else cur = q;
            break;
        }

        if (cur == null) {
            if (served) { a.outcome = "TOO_LATE"; return a; }
            Request q = reserve(bestId, seat, r);
            if (q == null) { a.outcome = "TOO_LATE"; return a; }
            q.channel = "BLUETOOTH";
            q.note = "Booked over Bluetooth in flight";
            q.updated = now();
            a.outcome = "NEW";
            a.request = q;
            return a;
        }

        a.previous = cur.mealName;
        String before = prefsKey(cur);
        boolean dietChanged = !cur.diet.equals(r.diet);
        String unsafe = currentUnsafeReason(cur, r, sr, dietChanged);

        if (unsafe == null) {
            a.outcome = "KEPT";
            copyPrefs(cur, r);
            if (!prefsKey(cur).equals(before)) {
                cur.amended = true;
                cur.channel = "BLUETOOTH";
                cur.status = "RESERVED";                         // crew must look at it again
                cur.note = "Preferences updated over Bluetooth. Current meal is still safe.";
                cur.updated = now();
            }
        } else {
            a.outcome = "CHANGED";
            a.why = unsafe;
            restock(cur);
            Request fresh = reserve(bestId, seat, r);
            if (fresh == null) { a.outcome = "TOO_LATE"; return a; }
            requests.remove(fresh);                              // keep the passenger's original request id
            cur.mealName = fresh.mealName;
            cur.kind = fresh.kind;
            cur.components = fresh.components;
            cur.kcal = fresh.kcal;
            copyPrefs(cur, r);
            cur.amended = true;
            cur.channel = "BLUETOOTH";
            cur.status = "RESERVED";
            cur.note = "Changed over Bluetooth from \"" + a.previous + "\": " + unsafe;
            cur.updated = now();
        }
        a.request = cur;
        return a;
    }

    /** Why the passenger's CURRENT choice is no longer ok, or null if it still is. */
    private String currentUnsafeReason(Request cur, Restrictions r, SearchResult sr, boolean dietChanged) {
        if (cur.kind.equals("MEAL")) {
            Meal m = findByName(menu, cur.mealName);
            if (m == null) return "meal not found";
            if (meatReason(m, r) != null) return meatReason(m, r);
            if (!matchesDiet(m, r.diet)) return "no longer matches your " + r.diet + " choice";
            // The food group is the main preference: if the diet changed and a meal of the new group exists, move to it.
            if (dietChanged && !primaryMatch(m, r)) {
                for (Meal a : sr.available) if (primaryMatch(a, r)) return "your diet changed to " + r.diet + " and a meal in that group is available";
            }
            return whyNot(m, r, false);                          // they already hold it, so stock does not matter
        }
        if (cur.kind.equals("PLATE")) {
            for (String name : cur.components.split(" \\+ ")) {
                Meal c = findByName(components, name.trim());
                if (c == null) continue;
                if (!matchesDiet(c, r.diet)) return name.trim() + " no longer matches your " + r.diet + " choice";
                String why = whyNot(c, r, false);
                if (why != null) return name.trim() + " " + why;
            }
            return null;
        }
        // CREW: the crew was asked to arrange something. If a safe option exists now, move to it.
        return (sr.available.isEmpty() && sr.plate == null) ? null : "a safe option is now available";
    }

    private Meal findByName(List<Meal> list, String name) {
        for (Meal m : list) if (m.name.equals(name)) return m;
        return null;
    }

    /** Gives the stock of a released meal or plate back. */
    private void restock(Request q) {
        if (q.kind.equals("MEAL")) {
            Meal m = findByName(menu, q.mealName);
            if (m != null && m.stock < m.start) m.stock++;
        } else if (q.kind.equals("PLATE")) {
            for (String name : q.components.split(" \\+ ")) {
                Meal c = findByName(components, name.trim());
                if (c != null && c.stock < c.start) c.stock++;
            }
        }
    }

    private void copyPrefs(Request q, Restrictions r) {
        q.diet = r.diet; q.noBeef = r.noBeef; q.noPork = r.noPork; q.spice = r.spice; q.severe = r.severe;
        q.allergies = new ArrayList<>(r.allergies);
        q.custom = new ArrayList<>(r.custom.keySet());
    }

    private String prefsKey(Request q) {
        List<String> al = new ArrayList<>(q.allergies); Collections.sort(al);
        List<String> cu = new ArrayList<>(q.custom); Collections.sort(cu);
        return q.diet + "|" + q.noBeef + "|" + q.noPork + "|" + q.severe + "|" + q.spice + "|" + al + "|" + cu;
    }

    private String now() { return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")); }

    /** The passenger's latest request (by seat) and Dietary Passport (by login id) as JSON: what a Bluetooth read returns. */
    public synchronized String latestJsonFor(String seat, String pid) {
        String passport = "null";
        Passport pp = pid == null || pid.isBlank() ? null : passports.get(pid.toLowerCase());
        if (pp != null) passport = pp.toJson();
        for (int i = requests.size() - 1; i >= 0; i--) {
            if (requests.get(i).seat.equalsIgnoreCase(seat))
                return "{\"found\":true,\"request\":" + requests.get(i).toJson() + ",\"passport\":" + passport + "}";
        }
        return "{\"found\":false,\"passport\":" + passport + "}";
    }

    /** The AI allergen analysis of every meal and galley component (for the monitor page). */
    public synchronized String analysisJson() {
        List<Meal> all = new ArrayList<>(menu);
        all.addAll(components);
        StringBuilder sb = new StringBuilder("[");
        for (Meal m : all) {
            MealAnalyzer.Analysis a = MealAnalyzer.analyze(m);
            if (sb.length() > 1) sb.append(",");
            sb.append("{\"id\":").append(Json.quote(m.id))
              .append(",\"name\":").append(Json.quote(m.name))
              .append(",\"kind\":").append(Json.quote(m.kind))
              .append(",\"named\":").append(Json.array(AllergenDb.allergensIn(m)))
              .append(",\"hidden\":").append(m.hiddenJson())
              .append(",\"source\":").append(Json.quote(a.source))
              .append(",\"ingredients\":").append(Json.array(m.ingredients)).append("}");
        }
        return sb.append("]").toString();
    }

    // ---------------------------------------------------------------
    //  Catering and crew
    // ---------------------------------------------------------------

    public synchronized List<Meal> getMenu() { return new ArrayList<>(menu); }
    public synchronized List<Meal> getComponents() { return new ArrayList<>(components); }

    public synchronized List<Request> getRequests() {
        List<Request> copy = new ArrayList<>(requests);
        Collections.reverse(copy);                           // newest first
        return copy;
    }

    public synchronized Request setStatus(int id, String status) {
        for (Request q : requests) {
            if (q.id == id) { q.status = status; return q; }
        }
        return null;
    }

    public synchronized void reset() {
        loadMenu();
        requests.clear();
        passports.clear();
        nextId = 1;
        approvedPlan = null;
    }

    // ---------------------------------------------------------------
    //  Waste planner: what should we load on the NEXT flight?
    // ---------------------------------------------------------------

    /**
     * Looks at how each meal sold and suggests the next load:
     *   sold out  -> load more (we lost sales)
     *   lagging   -> load fewer (it would be thrown away)
     * It also totals the money sitting in uneaten food.
     */
    public synchronized String plannerJson() {
        int totalStart = 0, totalUsed = 0;
        for (Meal m : menu) { totalStart += m.start; totalUsed += m.start - m.stock; }
        double overall = totalStart == 0 ? 0 : (double) totalUsed / totalStart;   // how much of the flight is "sold"

        double leftoverValue = 0, extraCost = 0, wasteAvoided = 0, currentLoad = 0;
        StringBuilder rows = new StringBuilder();

        for (int i = 0; i < menu.size(); i++) {
            Meal m = menu.get(i);
            int used = m.start - m.stock;
            double usedPct = (double) used / m.start;

            String status = "OK";
            int suggest = m.start;
            if (m.stock == 0) {
                status = "SOLD_OUT";
                suggest = m.start + Math.max(2, (int) Math.ceil(m.start * 0.25));    // demand was higher than we loaded
            } else if (m.stock <= 2 || (double) m.stock / m.start <= 0.25) {
                status = "LOW";
                suggest = Math.max(m.start, (int) Math.ceil(used * 1.1) + 1);          // nearly sold out: never load less
            } else if (overall >= 0.3 && usedPct < 0.5 * overall) {
                status = "UNDERUSED";
                suggest = Math.max((int) Math.ceil(m.start * 0.3), (int) Math.ceil(used * 1.2));   // closer to real demand, but keep a minimum
            }
            int delta = suggest - m.start;
            if (delta > 0) extraCost += delta * m.cost;
            if (delta < 0) wasteAvoided += -delta * m.cost;
            leftoverValue += m.stock * m.cost;
            currentLoad += m.start * m.cost;

            if (i > 0) rows.append(",");
            rows.append("{\"id\":").append(Json.quote(m.id))
                .append(",\"cost\":").append(m.cost)
                .append(",\"name\":").append(Json.quote(m.name))
                .append(",\"start\":").append(m.start)
                .append(",\"stock\":").append(m.stock)
                .append(",\"used\":").append(used)
                .append(",\"status\":").append(Json.quote(status))
                .append(",\"suggest\":").append(suggest)
                .append(",\"delta\":").append(delta)
                .append(",\"leftoverCost\":").append(Math.round(m.stock * m.cost * 100) / 100.0).append("}");
        }

        int plates = 0, crewArranged = 0;
        for (Request q : requests) {
            if (q.kind.equals("PLATE")) plates++;
            if (q.kind.equals("CREW")) crewArranged++;
        }
        return "{\"rows\":[" + rows + "]"
            + ",\"soldPercent\":" + Math.round(overall * 100)
            + ",\"leftoverValue\":" + Math.round(leftoverValue * 100) / 100.0
            + ",\"wasteAvoided\":" + Math.round(wasteAvoided * 100) / 100.0
            + ",\"extraCost\":" + Math.round(extraCost * 100) / 100.0
            + ",\"currentLoadCost\":" + Math.round(currentLoad * 100) / 100.0
            + ",\"approved\":" + approvedJson()
            + ",\"plates\":" + plates
            + ",\"crewArranged\":" + crewArranged + "}";
    }

    /** Catering approves the quantities to load on the next flight (meal id -> quantity). */
    public synchronized void approvePlan(Map<String, Integer> plan) {
        approvedPlan = new LinkedHashMap<>();
        for (Meal m : menu) {
            int qty = Math.max(0, Math.min(500, plan.getOrDefault(m.id, m.start)));
            approvedPlan.put(m.id, qty);
        }
        approvedAt = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private String approvedJson() {
        if (approvedPlan == null) return "null";
        double cost = 0;
        StringBuilder rows = new StringBuilder();
        for (Meal m : menu) {
            int qty = approvedPlan.get(m.id);
            cost += qty * m.cost;
            if (rows.length() > 0) rows.append(",");
            rows.append("{\"name\":").append(Json.quote(m.name)).append(",\"start\":").append(m.start)
                .append(",\"qty\":").append(qty).append("}");
        }
        return "{\"time\":" + Json.quote(approvedAt) + ",\"cost\":" + Math.round(cost * 100) / 100.0
             + ",\"rows\":[" + rows + "]}";
    }

    // ---------------------------------------------------------------
    //  Demo helper: simulate boarding so the planner has data to show
    // ---------------------------------------------------------------

    /** How popular each meal is with passengers (mock). Bigger = chosen more often. */
    static final Map<String, Integer> POPULARITY = Map.of(
            "thali", 6, "paneer", 5, "pasta", 1, "jain", 1, "vegan", 2, "chicken", 6,
            "salmon", 3, "gyudon", 2, "tonkatsu", 1, "maki", 2);

    static int popularity(String id) { return POPULARITY.getOrDefault(id, 1); }

    /** Real passengers do not all take the top suggestion: pick one at random, weighted by popularity. */
    private Meal popularPick(List<Meal> options, Random rnd) {
        int total = 0;
        for (Meal m : options) total += POPULARITY.getOrDefault(m.id, 1);
        int roll = rnd.nextInt(total);
        for (Meal m : options) {
            roll -= POPULARITY.getOrDefault(m.id, 1);
            if (roll < 0) return m;
        }
        return options.get(0);
    }

    /** One random passenger profile (mock mix of Indian passengers on a Delhi-Narita flight). */
    private Restrictions randomRestrictions(Random rnd) {
        String[] standard = AllergenDb.KEYWORDS.keySet().toArray(new String[0]);
        String[] typed = {"kiwi", "avocado", "nightshades"};
        Restrictions r = new Restrictions();
        int d = rnd.nextInt(100);
        r.diet = d < 32 ? "vegetarian" : d < 80 ? "nonveg" : d < 88 ? "vegan" : "jain";
        if (r.diet.equals("nonveg")) { r.noBeef = rnd.nextInt(100) < 30; r.noPork = rnd.nextInt(100) < 12; }
        int s = rnd.nextInt(100);
        r.spice = s < 35 ? "spicy" : s < 75 ? "mild" : "low";
        if (rnd.nextInt(100) < 14) {
            r.allergies.add(standard[rnd.nextInt(standard.length)]);
            if (rnd.nextInt(100) < 30) {
                String a = standard[rnd.nextInt(standard.length)];
                if (!r.allergies.contains(a)) r.allergies.add(a);
            }
            r.severe = rnd.nextInt(100) < 25;
        }
        if (rnd.nextInt(100) < 4) {
            String t = typed[rnd.nextInt(typed.length)];
            r.custom.put(t, AllergenExpander.expand(t).keywords);
        }
        if (rnd.nextInt(100) < 15) {
            String[] meatLikes = {"chicken", "fish", "salmon"};
            r.likes.add(r.diet.equals("nonveg") ? meatLikes[rnd.nextInt(3)] : "paneer");
        }
        return r.normalize();
    }

    /** Creates n random passengers who each choose a safe option. */
    public synchronized void simulate(int n) {
        Random rnd = new Random(7 + requests.size());
        for (int i = 0; i < n; i++) {
            Restrictions r = randomRestrictions(rnd);
            String seat = (1 + rnd.nextInt(40)) + "" + "ABCDEFGHJK".charAt(rnd.nextInt(10));
            SearchResult sr = search(r);
            Request q;
            if (!sr.available.isEmpty())      q = reserve(popularPick(sr.available, rnd).id, seat, r);
            else if (sr.plate != null)        q = reserve("PLATE", seat, r);
            else                              q = reserve("CREW", seat, r);

            if (q != null) savePassport("sim" + q.id + "@demo", seat, "", r, "BOOKING", q.mealName);
            if (q != null && i < n - 3) q.status = "SERVED";    // keep the crew screen tidy: last 3 stay open
        }
    }

    // ---------------------------------------------------------------
    //  Dietary Passport
    // ---------------------------------------------------------------

    /**
     * Creates or updates a passenger's Dietary Passport. Called when the ticket is booked, when the passenger edits
     * on the web, and (automatically) for every Bluetooth edit in flight.
     * @param via BOOKING, APP or BLUETOOTH
     * @param preOrder name of the pre-ordered meal, or null to keep the old one
     */
    public synchronized Passport savePassport(String id, String seat, String name, Restrictions r, String via, String preOrder) {
        String key = id.trim().toLowerCase();
        Passport p = passports.get(key);
        boolean isNew = p == null;
        if (isNew) { p = new Passport(key); passports.put(key, p); }
        r.normalize();
        List<String> changes = isNew ? new ArrayList<>(List.of("passport created")) : Passport.diff(p.prefs, r);
        boolean seatChanged = seat != null && !seat.isBlank() && !seat.equalsIgnoreCase(p.seat);
        if (!isNew && changes.isEmpty() && !seatChanged && preOrder == null) return p;      // nothing new: keep the version

        p.prefs = r.copy();
        if (seat != null && !seat.isBlank()) p.seat = seat.toUpperCase();
        if (name != null && !name.isBlank()) p.name = name;
        if (preOrder != null) p.preOrder = preOrder;
        p.version++;
        p.updated = now();
        p.via = via;
        p.pendingSync = via.equals("BLUETOOTH");               // an aircraft edit must still reach the ground system
        p.history.add(0, "v" + p.version + " " + p.updated + " via " + via + ": " + (changes.isEmpty() ? "booking updated" : String.join(", ", changes)));
        while (p.history.size() > 8) p.history.remove(p.history.size() - 1);
        return p;
    }

    public synchronized Passport getPassport(String id) { return id == null ? null : passports.get(id.trim().toLowerCase()); }
    synchronized List<Passport> passportList() { return new ArrayList<>(passports.values()); }

    /**
     * Demo: a fresh flight with n passengers who each have a Dietary Passport. preShare percent have already
     * pre-ordered a meal at booking (stock goes down); the rest have only a passport. The forecaster predicts them.
     */
    public synchronized void seedManifest(int n, int preShare) {
        reset();
        Random rnd = new Random(11);
        for (int i = 0; i < n; i++) {
            Restrictions r = randomRestrictions(rnd);
            String seat = (1 + i / 10) + "" + "ABCDEFGHJK".charAt(i % 10);
            String id = String.format("pax%03d@demo", i + 1);
            String pre = null;
            if (rnd.nextInt(100) < preShare) {
                SearchResult sr = search(r);
                Request q = null;
                if (!sr.available.isEmpty())  q = reserve(popularPick(sr.available, rnd).id, seat, r);
                else if (sr.plate != null)    q = reserve("PLATE", seat, r);
                if (q != null) pre = q.mealName;
            }
            savePassport(id, seat, "Passenger " + (i + 1), r, "BOOKING", pre == null ? "" : pre);
        }
    }

    // ---------------------------------------------------------------
    //  AI model 2: demand and custom-plate forecast from passports + stock
    // ---------------------------------------------------------------

    public String forecastJson(boolean explain) {
        DemandForecaster.Result result;
        synchronized (this) { result = DemandForecaster.run(this); }
        return result.toJson(explain);              // the optional Claude call happens OUTSIDE the lock
    }
}
