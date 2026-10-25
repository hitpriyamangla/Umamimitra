package com.umamimitra.galley;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One small place that talks to Claude. Used only if ANTHROPIC_API_KEY is set. */
public class Claude {

    static boolean enabled() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        return key != null && !key.isBlank();
    }

    /** Sends one question to Claude and returns its text answer. */
    static String ask(String system, String user) throws Exception {
        String model = System.getenv().getOrDefault("CLAUDE_MODEL", "claude-sonnet-5-5");
        String body = "{\"model\":" + Json.quote(model) + ",\"max_tokens\":300,"
            + "\"system\":" + Json.quote(system) + ","
            + "\"messages\":[{\"role\":\"user\",\"content\":" + Json.quote(user) + "}]}";

        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
            .timeout(Duration.ofSeconds(8))
            .header("content-type", "application/json")
            .header("x-api-key", System.getenv("ANTHROPIC_API_KEY"))
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        String response = HttpClient.newHttpClient()
            .send(request, HttpResponse.BodyHandlers.ofString()).body();

        // Pull the reply text out of Claude's JSON answer: ..."text":"line1\nline2"...
        Matcher m = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(response);
        if (!m.find()) throw new Exception("unexpected answer from Claude");
        return m.group(1).replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
