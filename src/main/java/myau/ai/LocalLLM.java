package myau.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Talks to the local model through Ollama's HTTP API ({@code /api/chat}, streamed). One request at
 * a time: a new question disconnects the one still running. Callbacks run on the client thread.
 * Nothing leaves the machine: the endpoint is the local Ollama server.
 */
public final class LocalLLM {
    /** Must match SYSTEM_PROMPT in tools/ai/filter.py and the SYSTEM line of the Ollama Modelfile. */
    public static final String SYSTEM_PROMPT = "你是 BetterMyau 的 PvP 军师。";
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 90000;
    private static final long PARTIAL_INTERVAL_MS = 60L;

    public interface Listener {
        /** The answer so far (grows while the model writes). */
        void onText(String soFar);

        void onDone(String answer);

        void onError(String message);
    }

    private static final Minecraft mc = Minecraft.getMinecraft();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BetterMyau-AI");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger generation = new AtomicInteger();
    private volatile HttpURLConnection current;

    /**
     * @param messages role/content pairs after the system prompt, oldest first; the last one is the
     *                 question being asked
     */
    public void chat(String endpoint, String model, List<String[]> messages, Listener listener) {
        int gen = this.cancel();
        this.worker.execute(() -> this.run(gen, endpoint, model, new ArrayList<>(messages), listener));
    }

    /** Stops the running request, if any; returns the new generation number. */
    public int cancel() {
        int gen = this.generation.incrementAndGet();
        HttpURLConnection connection = this.current;
        if (connection != null) connection.disconnect();
        return gen;
    }

    /** Model names the server has, or throws with a readable message. Blocking: call off the client thread. */
    public static List<String> listModels(String endpoint) throws IOException {
        HttpURLConnection c = open(endpoint, "/api/tags", "GET");
        try {
            JsonObject body = new JsonParser().parse(readAll(c)).getAsJsonObject();
            List<String> names = new ArrayList<>();
            JsonArray models = body.has("models") ? body.getAsJsonArray("models") : new JsonArray();
            for (JsonElement m : models) names.add(m.getAsJsonObject().get("name").getAsString());
            return names;
        } finally {
            c.disconnect();
        }
    }

    public ExecutorService worker() {
        return this.worker;
    }

    // ---------------------------------------------------------------- request

    private void run(int gen, String endpoint, String model, List<String[]> messages, Listener listener) {
        if (gen != this.generation.get()) return;
        StringBuilder answer = new StringBuilder();
        HttpURLConnection c = null;
        try {
            c = open(endpoint, "/api/chat", "POST");
            this.current = c;
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = requestBody(model, messages).getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            int code = c.getResponseCode();
            if (code != 200) {
                String error = errorMessage(c, code);
                if (gen == this.generation.get()) post(() -> listener.onError(error));
                return;
            }
            long lastPost = 0L;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (gen != this.generation.get()) return;
                    if (line.trim().isEmpty()) continue;
                    JsonObject chunk = new JsonParser().parse(line).getAsJsonObject();
                    if (chunk.has("error")) {
                        String error = chunk.get("error").getAsString();
                        post(() -> listener.onError(error));
                        return;
                    }
                    if (chunk.has("message")) {
                        JsonElement content = chunk.getAsJsonObject("message").get("content");
                        if (content != null && !content.isJsonNull()) answer.append(content.getAsString());
                    }
                    boolean done = chunk.has("done") && chunk.get("done").getAsBoolean();
                    long now = System.currentTimeMillis();
                    if (!done && now - lastPost >= PARTIAL_INTERVAL_MS) {
                        lastPost = now;
                        String soFar = clean(answer.toString());
                        post(() -> {
                            if (gen == this.generation.get()) listener.onText(soFar);
                        });
                    }
                    if (done) break;
                }
            }
            String full = clean(answer.toString());
            post(() -> {
                if (gen == this.generation.get()) listener.onDone(full);
            });
        } catch (Exception e) {
            if (gen != this.generation.get()) return; // cancelled: the disconnect threw
            String message = describe(e, endpoint);
            post(() -> listener.onError(message));
        } finally {
            if (c != null) c.disconnect();
            if (this.current == c) this.current = null;
        }
    }

    private static String requestBody(String model, List<String[]> messages) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", true);
        body.addProperty("keep_alive", "30m");
        body.addProperty("think", false);
        JsonArray list = new JsonArray();
        list.add(message("system", SYSTEM_PROMPT));
        for (String[] m : messages) list.add(message(m[0], m[1]));
        body.add("messages", list);
        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.4);
        options.addProperty("num_predict", 400);
        body.add("options", options);
        return body.toString();
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    /** Drops a thinking block if a base model still writes one. */
    static String clean(String text) {
        String t = text;
        int open = t.indexOf("<think>");
        if (open >= 0) {
            int close = t.indexOf("</think>", open);
            t = close < 0 ? t.substring(0, open) : t.substring(0, open) + t.substring(close + "</think>".length());
        }
        return t.trim();
    }

    private static HttpURLConnection open(String endpoint, String path, String method) throws IOException {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        // never through a proxy: a launcher's -DsocksProxyHost would otherwise route the local server away
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection(Proxy.NO_PROXY);
        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setUseCaches(false);
        return c;
    }

    private static String readAll(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code != 200) throw new IOException(errorMessage(c, code));
        try (InputStream in = c.getInputStream()) {
            return readStream(in);
        }
    }

    private static String errorMessage(HttpURLConnection c, int code) {
        try (InputStream in = c.getErrorStream()) {
            if (in != null) {
                String text = readStream(in);
                try {
                    JsonObject o = new JsonParser().parse(text).getAsJsonObject();
                    if (o.has("error")) return o.get("error").getAsString();
                } catch (Exception ignored) {
                }
                if (!text.isEmpty()) return "HTTP " + code + ": " + text;
            }
        } catch (IOException ignored) {
        }
        return "HTTP " + code;
    }

    private static String readStream(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    static String describe(Exception e, String endpoint) {
        if (e instanceof java.net.ConnectException) return "连不上 Ollama（" + endpoint + "），先打开 Ollama";
        if (e instanceof java.net.SocketTimeoutException) return "模型太久没回应，可能还在加载";
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static void post(Runnable r) {
        mc.addScheduledTask(r);
    }
}
