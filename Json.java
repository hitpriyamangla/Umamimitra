package com.umamimitra.galley;

import java.util.List;

/** Tiny helpers for writing JSON by hand (so we need no libraries). */
public class Json {

    /** Wraps text in quotes and escapes characters that would break JSON. */
    static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                          .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    /** ["a","b","c"] */
    static String array(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(quote(items.get(i)));
        }
        return sb.append("]").toString();
    }
}
