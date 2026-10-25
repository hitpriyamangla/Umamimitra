package com.umamimitra.galley;

import java.util.List;

/**
 * One passenger's request. The same object is seen by passenger, catering and crew:
 * this is our "one shared state".
 */
public class Request {
    int id;
    String seat;
    String mealName;
    String kind = "MEAL";          // MEAL, PLATE (custom safe plate), CREW (crew must arrange)
    String components = "";        // for PLATE: "Steamed rice + Boiled lentils + Fresh fruit cup"
    String diet;
    boolean noBeef, noPork;
    List<String> allergies;        // standard allergens
    List<String> custom;           // allergies the passenger typed themselves
    boolean severe;                // passenger marked the allergy as severe
    String spice;
    String status = "RESERVED";    // RESERVED -> ACKNOWLEDGED -> SERVED
    String time;
    String channel = "APP";        // APP (booked on the ground) or BLUETOOTH (edited in flight)
    boolean amended;               // changed in flight: the crew must re-check
    String note = "";              // e.g. "Changed from Veg Thali: contains soy"
    String updated = "";           // time of the last in-flight change
    int kcal;                      // estimated calories of the whole meal or plate

    String toJson() {
        return "{\"id\":" + id
             + ",\"seat\":" + Json.quote(seat)
             + ",\"meal\":" + Json.quote(mealName)
             + ",\"kind\":" + Json.quote(kind)
             + ",\"components\":" + Json.quote(components)
             + ",\"diet\":" + Json.quote(diet)
             + ",\"noBeef\":" + noBeef
             + ",\"noPork\":" + noPork
             + ",\"allergies\":" + Json.array(allergies)
             + ",\"custom\":" + Json.array(custom)
             + ",\"severe\":" + severe
             + ",\"spice\":" + Json.quote(spice)
             + ",\"status\":" + Json.quote(status)
             + ",\"time\":" + Json.quote(time)
             + ",\"channel\":" + Json.quote(channel)
             + ",\"amended\":" + amended
             + ",\"note\":" + Json.quote(note)
             + ",\"updated\":" + Json.quote(updated)
             + ",\"kcal\":" + kcal + "}";
    }
}
