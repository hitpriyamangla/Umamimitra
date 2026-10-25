package com.umamimitra.galley;

import java.util.*;

/**
 * AI model 2: the Demand and Custom-Plate Forecaster.
 *
 * It reads every Dietary Passport on the manifest together with the live stock and predicts:
 *   1. how many portions of each meal the passengers who have NOT ordered yet will want,
 *   2. how many custom safe plates are needed (certain + at risk + a buffer for unexpected orders),
 *   3. which galley components will run short, and
 *   4. which EXTRA components to load, so a passenger who orders unexpectedly (or changes diet in flight)
 *      always has something safe to eat.
 *
 * How it works (honest version): it is a transparent statistical model, not a trained neural net.
 * For each passport it runs the same safety search the passenger sees, then spreads one portion over the safe
 * options by rank and popularity. The "unexpected" share is an adjustable assumption (UNEXPECTED_RATE).
 * It works fully offline on the aircraft or ground server. With an API key, Claude only WRITES the plain-language
 * briefing from these numbers; it never changes them.
 */
public class DemandForecaster {

    /** Share of passengers who will order or change something we have no passport data for (assumption). */
    static final double UNEXPECTED_RATE = 0.10;
    /** Every diet and allergy combination should keep at least this many safe portions in reserve. */
    static final int SAFETY_MIN = 2;

    // ---------------------------------------------------------------

    static class Scenario {
        String label; Restrictions r; int need; int capacity; boolean fixed;
        int whole, main, carb;
        Scenario(String label, Restrictions r) { this.label = label; this.r = r; }
        boolean noPlate()  { return whole == 0 && (main == 0 || carb == 0); }
        int capacityNow()  { return whole + Math.min(main, carb); }
    }

    static class Extra {
        String name, kind; int qty; double unit; List<String> serves = new ArrayList<>();
    }

    public static class Result {
        int passengers, preOrdered, pending, unexpected;
        int definite, atRisk, buffer, recommended, crewNeeded, existingPlates;
        List<String> componentRows = new ArrayList<>();
        List<String> mealRows = new ArrayList<>();
        List<String> extraRows = new ArrayList<>();
        List<String> gapRows = new ArrayList<>();
        List<String> shortMeals = new ArrayList<>(), extraNames = new ArrayList<>();
        int unresolved;
        String briefing = "";

        String toJson(boolean explain) {
            String source = "rules";
            String text = briefing;
            if (explain && Claude.enabled()) {
                try {
                    String system = "You are an airline catering planner's assistant. In at most 3 short sentences, explain the numbers below "
                        + "for a catering manager: how many custom plates to load and why, which meals are short, and which extras to add. "
                        + "Use only the numbers given. Plain words, no markdown.";
                    text = Claude.ask(system, briefing).trim();
                    source = "claude";
                } catch (Exception e) {
                    System.out.println("Claude briefing failed, using rules text: " + e.getMessage());
                }
            }
            return "{\"passengers\":" + passengers + ",\"preOrdered\":" + preOrdered + ",\"pending\":" + pending
                + ",\"unexpectedRate\":" + UNEXPECTED_RATE + ",\"unexpected\":" + unexpected
                + ",\"plates\":{\"definite\":" + definite + ",\"atRisk\":" + atRisk + ",\"buffer\":" + buffer
                + ",\"recommended\":" + recommended + ",\"crewNeeded\":" + crewNeeded + ",\"existing\":" + existingPlates + "}"
                + ",\"components\":[" + String.join(",", componentRows) + "]"
                + ",\"meals\":[" + String.join(",", mealRows) + "]"
                + ",\"extras\":[" + String.join(",", extraRows) + "]"
                + ",\"gaps\":[" + String.join(",", gapRows) + "],\"unresolved\":" + unresolved
                + ",\"briefing\":" + Json.quote(text) + ",\"source\":" + Json.quote(source)
                + ",\"model\":\"Demand and custom-plate forecaster (passports + live stock)\"}";
        }
    }

    // ---------------------------------------------------------------

    static Result run(MealService svc) {
        Result res = new Result();
        List<Passport> pax = svc.passportList();
        List<Meal> menu = svc.getMenu(), comps = svc.getComponents();
        res.passengers = pax.size();

        // Latest request per seat: a passenger with a request has already ordered.
        Map<String, Request> bySeat = new HashMap<>();
        for (Request q : svc.getRequests()) {
            bySeat.putIfAbsent(q.seat.toLowerCase(), q);
            if (q.kind.equals("PLATE")) res.existingPlates++;
        }

        Map<String, Double> demand = new LinkedHashMap<>();
        for (Meal m : menu) demand.put(m.id, 0.0);
        Map<String, Double> compUse = new HashMap<>();
        List<Passport> pending = new ArrayList<>();
        List<Object[]> fragile = new ArrayList<>();             // {passport, only meal id}

        for (Passport p : pax) {
            if (bySeat.containsKey(p.seat.toLowerCase())) { res.preOrdered++; continue; }
            res.pending++;
            pending.add(p);
            MealService.SearchResult sr = svc.search(p.prefs.copy());
            if (!sr.available.isEmpty()) {
                int n = sr.available.size();
                double[] w = new double[n]; double sum = 0;
                for (int i = 0; i < n; i++) {
                    Meal m = sr.available.get(i);
                    int likes = 0;
                    for (String l : p.prefs.likes) if (!l.isBlank() && m.searchText().contains(l.toLowerCase())) likes++;
                    w[i] = MealService.popularity(m.id) * (1 + likes) / (1 + 0.6 * i);     // better rank and popular meals are chosen more
                    sum += w[i];
                }
                for (int i = 0; i < n; i++) demand.merge(sr.available.get(i).id, w[i] / sum, Double::sum);
                if (n == 1) fragile.add(new Object[]{p, sr.available.get(0).id});
            } else if (sr.plate != null) {
                res.definite++;
                for (Meal c : sr.plate) compUse.merge(c.id, 1.0, Double::sum);
            } else {
                res.crewNeeded++;                                // not even a plate: needs extra stock (see extras)
            }
        }

        // A passenger whose ONLY safe meal will probably sell out becomes a plate passenger.
        Map<String, Integer> stock = new HashMap<>();
        for (Meal m : menu) stock.put(m.id, m.stock);
        for (Object[] f : fragile) {
            Passport p = (Passport) f[0];
            String id = (String) f[1];
            if (demand.get(id) < stock.get(id)) continue;
            List<Meal> plate = svc.plateFor(p.prefs.copy());
            if (plate == null) { res.crewNeeded++; continue; }
            res.atRisk++;
            for (Meal c : plate) compUse.merge(c.id, 1.0, Double::sum);
        }

        // Unexpected orders: passengers with no passport data, or who change diet in flight.
        res.unexpected = (int) Math.ceil(UNEXPECTED_RATE * res.passengers);
        double plateShare = Math.max(0.04, (res.definite + res.atRisk) / (double) Math.max(1, res.pending));
        res.buffer = res.passengers == 0 ? 0 : Math.max(1, (int) Math.ceil(res.unexpected * plateShare));
        res.recommended = res.definite + res.atRisk + res.buffer;

        // ---- Meal rows ----
        double demandTotal = 0;
        for (double d : demand.values()) demandTotal += d;
        List<String> shortNames = res.shortMeals;
        for (Meal m : menu) {
            double expected = demand.get(m.id);
            double share = demandTotal == 0 ? 0 : expected / demandTotal;
            double buf = res.unexpected * share;
            int recommended = (int) Math.ceil(expected + buf);
            int gap = recommended - m.stock;
            String status = gap > 0 ? "SHORT" : m.stock > recommended * 1.6 + 2 ? "SURPLUS" : "OK";
            if (gap > 0) shortNames.add(m.name + " (+" + gap + ")");
            res.mealRows.add("{\"id\":" + Json.quote(m.id) + ",\"name\":" + Json.quote(m.name)
                + ",\"stock\":" + m.stock + ",\"expected\":" + round1(expected) + ",\"buffer\":" + round1(buf)
                + ",\"recommended\":" + recommended + ",\"gap\":" + gap + ",\"status\":" + Json.quote(status) + "}");
        }

        // ---- Component needs (plates already counted, plus the buffer plates at the average mix) ----
        double usedTotal = 0;
        for (double d : compUse.values()) usedTotal += d;
        double perPlateScale = usedTotal == 0 ? 0 : (double) res.buffer / Math.max(1, res.definite + res.atRisk);
        for (Meal c : comps) {
            double use = compUse.getOrDefault(c.id, 0.0);
            if (use == 0 && usedTotal > 0) continue;
            double need = use * (1 + perPlateScale);
            if (usedTotal == 0) need = (c.id.equals("c_lentils") || c.id.equals("c_rice") || c.id.equals("c_fruit")) ? res.buffer : 0;
            int needInt = (int) Math.ceil(need);
            if (needInt == 0) continue;
            int shortage = Math.max(0, needInt - c.stock);
            res.componentRows.add("{\"name\":" + Json.quote(c.name) + ",\"kind\":" + Json.quote(c.kind)
                + ",\"stock\":" + c.stock + ",\"need\":" + needInt + ",\"shortfall\":" + shortage + "}");
        }

        // ---- Unexpected-order coverage and extras to stock ----
        extras(svc, res, pending);

        // ---- Plain-language briefing (rules). Claude may rewrite it, never the numbers. ----
        StringBuilder b = new StringBuilder();
        b.append(res.passengers).append(" passengers: ").append(res.preOrdered).append(" pre-ordered, ").append(res.pending).append(" still to order. ");
        b.append("Load ").append(res.recommended).append(" custom plates (").append(res.definite).append(" certain from passports, ")
         .append(res.atRisk).append(" at risk if a sole safe meal sells out, ").append(res.buffer).append(" buffer for unexpected orders). ");
        if (!shortNames.isEmpty()) b.append("Meals likely short: ").append(String.join(", ", shortNames)).append(". ");
        else b.append("No whole meal is predicted to run short. ");
        if (!res.extraNames.isEmpty()) b.append("Add extras: ").append(String.join(", ", res.extraNames)).append(". ");
        else if (res.unresolved == 0) b.append("Every tested diet and allergy combination is covered by current stock. ");
        if (res.unresolved > 0) b.append(res.unresolved).append(" combination(s) still have no safe option: crew must arrange.");
        res.briefing = b.toString().trim();
        return res;
    }

    // ---------------------------------------------------------------
    //  Scenarios and extras
    // ---------------------------------------------------------------

    private static Restrictions sc(String diet, String allergens, String custom) {
        Restrictions r = new Restrictions();
        r.diet = diet;
        if (!allergens.isEmpty()) r.allergies.addAll(Arrays.asList(allergens.split(",")));
        if (!custom.isEmpty()) r.custom.put(custom, AllergenExpander.expand(custom).keywords);
        return r.normalize();
    }

    private static String label(Restrictions r) {
        List<String> parts = new ArrayList<>();
        for (String a : r.allergies) parts.add(AllergenDb.LABELS.get(a).toLowerCase());
        parts.addAll(r.custom.keySet());
        String diet = r.diet.equals("nonveg") ? "Non-veg" : Character.toUpperCase(r.diet.charAt(0)) + r.diet.substring(1);
        return diet + (parts.isEmpty() ? "" : " · no " + String.join(" + ", parts));
    }

    private static List<Meal> candidates() {
        List<Meal> c = new ArrayList<>();
        c.add(Meal.component("x_quinoa", "Plain quinoa", "MAIN", Meal.Type.VEGAN, true, 0, 2.0, "quinoa,salt").nutrition(220, 8, 39, 4, 5, 10));
        c.add(Meal.component("x_chickpea", "Boiled chickpeas (plain)", "MAIN", Meal.Type.VEGAN, true, 0, 1.5, "chickpeas,salt,cumin").nutrition(270, 15, 45, 4, 12, 220));
        c.add(Meal.component("x_millet", "Steamed millet", "CARB", Meal.Type.VEGAN, true, 0, 1.0, "millet,salt").nutrition(210, 6, 41, 2, 3, 10));
        c.add(Meal.component("x_sweetpotato", "Baked sweet potato", "CARB", Meal.Type.VEGAN, false, 0, 1.2, "sweet potato,salt").nutrition(180, 4, 41, 0, 6, 70));
        c.add(Meal.component("x_pumpkin", "Steamed pumpkin", "SIDE", Meal.Type.VEGAN, true, 0, 1.2, "pumpkin,salt").nutrition(70, 2, 17, 0, 3, 20));
        c.add(Meal.component("x_corn", "Boiled sweet corn", "SIDE", Meal.Type.VEGAN, true, 0, 1.0, "sweet corn,salt").nutrition(130, 5, 29, 2, 4, 15));
        return c;
    }

    private static void extras(MealService svc, Result res, List<Passport> pending) {
        // Test scenarios: every diet alone, every diet with each single allergen, and some hard combinations.
        List<Scenario> list = new ArrayList<>();
        for (String diet : new String[]{"vegetarian", "vegan", "jain", "nonveg"}) {
            list.add(new Scenario(label(sc(diet, "", "")), sc(diet, "", "")));
            for (String a : AllergenDb.KEYWORDS.keySet()) list.add(new Scenario(label(sc(diet, a, "")), sc(diet, a, "")));
        }
        String[][] hard = {
            {"vegan", "soy,sesame,peanuts,gluten", ""}, {"vegetarian", "dairy,egg,gluten,peanuts", ""},
            {"jain", "dairy,gluten", ""}, {"jain", "dairy,gluten,sesame,peanuts", ""},
            {"nonveg", "fish,shellfish,egg,dairy", ""}, {"vegan", "", "legume"}, {"jain", "dairy", "legume"},
            {"vegetarian", "dairy,egg", "legume"}};
        for (String[] h : hard) list.add(new Scenario(label(sc(h[0], h[1], h[2])), sc(h[0], h[1], h[2])));

        for (Scenario s : list) {
            int manifest = 0;                                   // passengers still to order who fit this scenario
            for (Passport p : pending) {
                if (p.prefs.diet.equals(s.r.diet) && p.prefs.allergies.containsAll(s.r.allergies)
                        && p.prefs.custom.keySet().containsAll(s.r.custom.keySet())) manifest++;
            }
            s.need = manifest + SAFETY_MIN;                     // plus a safety reserve for unexpected orders
            int[] pr = svc.probe(s.r.copy(), null);
            s.whole = pr[0]; s.main = pr[1]; s.carb = pr[2];
            s.capacity = s.capacityNow();
        }

        // Greedy: add the extra that fixes the most weighted deficit, up to 4 extras.
        List<Meal> chosen = new ArrayList<>();
        List<Meal> pool = candidates();
        Map<String, Extra> picked = new LinkedHashMap<>();
        for (int round = 0; round < 4; round++) {
            Meal best = null; double bestValue = 0;
            for (Meal c : pool) {
                double value = 0;
                for (Scenario s : list) {
                    int deficit = s.need - capacityWith(svc, s, chosen);
                    if (deficit <= 0) continue;
                    Meal probe = copyWithStock(c, 40);
                    List<Meal> test = new ArrayList<>(chosen); test.add(probe);
                    int gain = Math.min(deficit, capacityWith(svc, s, test) - capacityWith(svc, s, chosen));
                    if (gain > 0) value += gain * (noPlateWith(svc, s, chosen) ? 5 : 1);
                }
                if (value > bestValue) { bestValue = value; best = c; }
            }
            if (best == null) break;
            // quantity = largest deficit it helps with, plus a little spare
            int qty = 0; List<String> serves = new ArrayList<>();
            for (Scenario s : list) {
                int deficit = s.need - capacityWith(svc, s, chosen);
                if (deficit <= 0) continue;
                List<Meal> test = new ArrayList<>(chosen); test.add(copyWithStock(best, 40));
                if (capacityWith(svc, s, test) > capacityWith(svc, s, chosen)) { qty = Math.max(qty, deficit); if (serves.size() < 4) serves.add(s.label); }
            }
            qty = Math.max(4, Math.min(40, qty + 2));
            chosen.add(copyWithStock(best, qty));
            pool.remove(best);
            Extra e = new Extra(); e.name = best.name; e.kind = best.kind; e.qty = qty; e.unit = best.cost; e.serves = serves;
            picked.put(best.id, e);
        }

        // Report: scenarios that were thin or empty BEFORE extras, and whether the extras fix them.
        for (Scenario s : list) {
            boolean gap = s.capacity < s.need;
            if (!gap) continue;
            int after = capacityWith(svc, s, chosen);
            s.fixed = after >= s.need;
            boolean stillNone = noPlateWith(svc, s, chosen);
            if (stillNone) res.unresolved++;
            String state = s.noPlate() ? "NO_PLATE" : "THIN";
            res.gapRows.add("{\"label\":" + Json.quote(s.label) + ",\"need\":" + s.need + ",\"capacity\":" + s.capacity
                + ",\"after\":" + after + ",\"state\":" + Json.quote(state) + ",\"fixed\":" + s.fixed + "}");
        }
        for (Extra e : picked.values()) {
            res.extraNames.add(e.name + " ×" + e.qty);
            res.extraRows.add("{\"name\":" + Json.quote(e.name) + ",\"kind\":" + Json.quote(e.kind) + ",\"qty\":" + e.qty
                + ",\"unit\":" + e.unit + ",\"total\":" + Math.round(e.qty * e.unit * 100) / 100.0
                + ",\"serves\":" + Json.array(e.serves) + "}");
        }
    }

    private static int capacityWith(MealService svc, Scenario s, List<Meal> extras) {
        int[] p = svc.probe(s.r.copy(), extras);
        return p[0] + Math.min(p[1], p[2]);
    }

    private static boolean noPlateWith(MealService svc, Scenario s, List<Meal> extras) {
        int[] p = svc.probe(s.r.copy(), extras);
        return p[0] == 0 && (p[1] == 0 || p[2] == 0);
    }

    private static Meal copyWithStock(Meal m, int stock) {
        Meal c = Meal.component(m.id, m.name, m.kind, m.type, m.jainSafe, stock, m.cost, String.join(",", m.ingredients));
        return c.nutrition(m.kcal, m.protein, m.carbs, m.fat, m.fibre, m.sodium);
    }

    private static double round1(double d) { return Math.round(d * 10) / 10.0; }
}
