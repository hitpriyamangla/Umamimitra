package com.umamimitra.link;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Just enough JSON for the link messages: one flat object whose values are text or whole numbers. */
public final class MiniJson {
    private MiniJson() {}

    public static String write(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            quote(sb, e.getKey()).append(':');
            if (e.getValue() instanceof Number) sb.append(((Number) e.getValue()).longValue());
            else quote(sb, String.valueOf(e.getValue()));
        }
        return sb.append('}').toString();
    }

    public static byte[] bytes(Map<String, Object> map) { return write(map).getBytes(StandardCharsets.UTF_8); }

    /** Text -> JSON string literal (also usable for building status pages). */
    public static String quote(String text) { return quote(new StringBuilder(), text).toString(); }

    private static StringBuilder quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        return sb.append('"');
    }

    public static Map<String, Object> parse(String json) {
        Parser p = new Parser(json);
        return p.object();
    }

    private static final class Parser {
        private final String s;
        private int i;
        Parser(String s) { this.s = s; }

        Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            ws(); expect('{'); ws();
            if (peek() == '}') { i++; return map; }
            while (true) {
                ws(); String key = string(); ws(); expect(':'); ws();
                map.put(key, peek() == '"' ? (Object) string() : (Object) number());
                ws();
                char c = next();
                if (c == '}') return map;
                if (c != ',') throw new IllegalArgumentException("bad JSON at " + i);
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = next();
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: sb.append(e);      // \" \\ \/
                }
            }
        }

        Long number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw new IllegalArgumentException("bad JSON at " + i);
            return (long) Double.parseDouble(s.substring(start, i));
        }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        char peek() { return i < s.length() ? s.charAt(i) : '\0'; }
        char next() { if (i >= s.length()) throw new IllegalArgumentException("bad JSON: ends early"); return s.charAt(i++); }
        void expect(char c) { if (next() != c) throw new IllegalArgumentException("bad JSON at " + (i - 1) + ": expected " + c); }
    }
}
