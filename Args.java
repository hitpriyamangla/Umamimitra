package com.umamimitra.link;

import java.util.HashMap;
import java.util.Map;

/** Reads "--name value" and "--flag" program arguments. */
public final class Args {
    private Args() {}

    public static Map<String, String> parse(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) map.put(key, args[++i]);
            else map.put(key, "true");
        }
        return map;
    }
}
