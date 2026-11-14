package com.chartsignal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Sends one screenshot to the Claude Messages API and returns a parsed signal. */
public class ClaudeClient {

    public static class Signal {
        public String signal;      // CALL, PUT or NO_TRADE
        public String confidence;  // low, medium, high
        public String reason;
    }

    private static final String URL_MESSAGES = "https://api.anthropic.com/v1/messages";

    private final String apiKey;
    private final String model;

    public ClaudeClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    private static String prompt(String expiry) {
        return "This is a live screenshot of a phone showing a binary options trading app. "
            + "The trader wants a signal for a trade with expiry " + expiry + ".\n\n"
            + "Ignore the small colored circle overlay; it is this app's own indicator.\n\n"
            + "Be calibrated. Over seconds to a few minutes price movement is mostly noise, "
            + "and the trader needs roughly 54% accuracy just to break even. So:\n"
            + "- Answer CALL (price will be higher at expiry) or PUT (lower) only when something "
            + "visible clearly tilts the odds: a strong trend on the chart's timeframe, price "
            + "rejecting a clear level, a decisive recent candle.\n"
            + "- Answer NO_TRADE when the chart is choppy, flat, mixed, unreadable, partly hidden, "
            + "or the screen is not showing a price chart.\n"
            + "- Confidence 'low' is normal for expiries under a minute. Use 'high' almost never.\n"
            + "- The reason is at most 15 words, describing only what is visible.";
    }

    public Signal analyze(String jpegBase64, String expiry) throws Exception {
        JSONObject schema = new JSONObject()
            .put("type", "object")
            .put("properties", new JSONObject()
                .put("signal", new JSONObject().put("type", "string")
                    .put("enum", new JSONArray().put("CALL").put("PUT").put("NO_TRADE")))
                .put("confidence", new JSONObject().put("type", "string")
                    .put("enum", new JSONArray().put("low").put("medium").put("high")))
                .put("reason", new JSONObject().put("type", "string")))
            .put("required", new JSONArray().put("signal").put("confidence").put("reason"))
            .put("additionalProperties", false);

        JSONArray content = new JSONArray()
            .put(new JSONObject()
                .put("type", "image")
                .put("source", new JSONObject()
                    .put("type", "base64")
                    .put("media_type", "image/jpeg")
                    .put("data", jpegBase64)))
            .put(new JSONObject().put("type", "text").put("text", prompt(expiry)));

        JSONObject body = new JSONObject()
            .put("model", model)
            .put("max_tokens", 4000)
            .put("output_config", new JSONObject()
                .put("effort", "low")
                .put("format", new JSONObject().put("type", "json_schema").put("schema", schema)))
            .put("messages", new JSONArray().put(new JSONObject()
                .put("role", "user").put("content", content)));

        HttpURLConnection conn = (HttpURLConnection) new URL(URL_MESSAGES).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);
        conn.setRequestProperty("content-type", "application/json");
        conn.setRequestProperty("x-api-key", apiKey);
        conn.setRequestProperty("anthropic-version", "2026-10-21");

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }

        int code = conn.getResponseCode();
        String text = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        conn.disconnect();

        if (code >= 400) {
            String msg = "HTTP " + code;
            try {
                msg = new JSONObject(text).getJSONObject("error").optString("message", msg);
            } catch (Exception ignored) { }
            if (code == 401) msg = "API key rejected";
            if (code == 429) msg = "Rate limited, slowing down";
            throw new Exception(msg);
        }

        JSONObject resp = new JSONObject(text);
        String stop = resp.optString("stop_reason");
        if ("refusal".equals(stop)) throw new Exception("Request declined");

        JSONArray blocks = resp.getJSONArray("content");
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject b = blocks.getJSONObject(i);
            if ("text".equals(b.optString("type"))) {
                JSONObject out = new JSONObject(b.getString("text"));
                Signal s = new Signal();
                s.signal = out.optString("signal", "NO_TRADE");
                s.confidence = out.optString("confidence", "low");
                s.reason = out.optString("reason", "");
                return s;
            }
        }
        throw new Exception("No answer in response");
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }
}
