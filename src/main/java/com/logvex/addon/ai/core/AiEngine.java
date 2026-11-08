package com.logvex.addon.ai.core;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The local AI, as the rest of the addon sees it.
 *
 * <p>Wraps {@link IntentModel} with the things a live feature needs: a confidence threshold, a
 * bounded reply cache so a spammy server does not reclassify the same line a hundred times, running
 * statistics, and JSON persistence so runtime teaching survives a restart.
 *
 * <p>Everything here runs on the caller's thread in microseconds. There is no model file to load, no
 * matrix multiply, and no allocation proportional to input length beyond the token list, which is
 * why this is safe to call from the game thread.
 */
public final class AiEngine {
    /** Cache size for recent classifications. Small and fixed: this is only for spam absorption. */
    private static final int CACHE_LIMIT = 128;
    /** Version of the persisted document, so a future build can migrate it. */
    public static final int STORE_VERSION = 1;

    private final IntentModel model = new IntentModel();
    private final Map<String, Cached> cache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_LIMIT;
        }
    };

    private boolean enabled = true;
    private boolean learnFromChat;
    private int maxReplyLength = 160;
    private long classified;
    private long understood;
    private long cacheHits;

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean learnFromChat() {
        return learnFromChat;
    }

    public void setLearnFromChat(boolean learnFromChat) {
        this.learnFromChat = learnFromChat;
    }

    public double threshold() {
        return model.threshold();
    }

    public void setThreshold(double threshold) {
        model.setThreshold(threshold);
        cache.clear();
    }

    public int maxReplyLength() {
        return maxReplyLength;
    }

    public void setMaxReplyLength(int maxReplyLength) {
        this.maxReplyLength = Math.max(16, maxReplyLength);
    }

    public IntentModel model() {
        return model;
    }

    /**
     * Classifies a message and produces a reply.
     *
     * @param text    the incoming message
     * @param player  the sender name, substituted for {@code {player}}
     * @param context extra placeholder values (for example {@code prefix}, {@code time})
     * @param seed    a seed for deterministic reply choice
     * @return the reply, or an empty string when the message was not understood
     */
    public String respond(String text, String player, Map<String, String> context, long seed) {
        if (!enabled) return "";
        String key = TextTokenizer.normalise(text);
        if (key.isEmpty()) return "";

        Cached cached = cache.get(key);
        IntentModel.Intent intent;
        if (cached != null) {
            cacheHits++;
            intent = cached.intentName() == null ? null : model.intent(cached.intentName());
        } else {
            classified++;
            intent = model.classify(text);
            cache.put(key, new Cached(intent == null ? null : intent.name()));
            if (intent != null) understood++;
        }
        if (intent == null) return "";

        String reply = model.reply(intent, seed, maxReplyLength);
        return fill(reply, player, context);
    }

    /** The intent name for a message, or null. Used by the GUI and the {@code .ai} command. */
    public String intentOf(String text) {
        IntentModel.Intent intent = model.classify(text);
        return intent == null ? null : intent.name();
    }

    /** The confidence of the best match, or 0. Used by the GUI and the {@code .ai} command. */
    public double confidence(String text) {
        return model.confidence(text);
    }

    /** Substitutes {@code {player}} and any values in {@code context} into a reply. */
    public static String fill(String template, String player, Map<String, String> context) {
        if (template == null || template.isEmpty()) return "";
        String out = template;
        if (player != null && !player.isBlank()) out = out.replace("{player}", player);
        if (context != null) {
            for (Map.Entry<String, String> entry : context.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                out = out.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return out;
    }

    public long classified() {
        return classified;
    }

    public long understood() {
        return understood;
    }

    public long cacheHits() {
        return cacheHits;
    }

    public double understandingRate() {
        return classified == 0 ? 0 : (double) understood / classified;
    }

    public void resetStats() {
        classified = 0;
        understood = 0;
        cacheHits = 0;
    }

    public void clearCache() {
        cache.clear();
    }

    // -------------------------------------------------------------- persistence

    /** Serialises the learned material and the tunables to JSON. */
    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", STORE_VERSION);
        root.addProperty("threshold", model.threshold());
        root.addProperty("learnFromChat", learnFromChat);
        root.addProperty("maxReplyLength", maxReplyLength);

        JsonObject taught = new JsonObject();
        for (Map.Entry<String, java.util.List<String>> entry : model.taughtExamples().entrySet()) {
            com.google.gson.JsonArray examples = new com.google.gson.JsonArray();
            for (String example : entry.getValue()) examples.add(example);
            taught.add(entry.getKey(), examples);
        }
        root.add("taught", taught);
        return root;
    }

    /** Restores tunables and any learned material. Never throws; a bad document is ignored. */
    public void loadJson(JsonObject root) {
        if (root == null) return;
        try {
            if (root.has("version") && root.get("version").getAsInt() > STORE_VERSION) return;
            if (root.has("threshold")) setThreshold(root.get("threshold").getAsDouble());
            if (root.has("learnFromChat")) learnFromChat = root.get("learnFromChat").getAsBoolean();
            if (root.has("maxReplyLength")) setMaxReplyLength(root.get("maxReplyLength").getAsInt());

            // Learned examples are replayed onto the built-in corpus rather than replacing it, so a
            // stored file from a build with fewer built-ins can never delete the seed intents.
            if (root.has("taught")) {
                JsonObject taught = root.getAsJsonObject("taught");
                for (Map.Entry<String, com.google.gson.JsonElement> entry : taught.entrySet()) {
                    if (!entry.getValue().isJsonArray()) continue;
                    for (com.google.gson.JsonElement element : entry.getValue().getAsJsonArray()) {
                        if (element.isJsonPrimitive()) {
                            model.replay(entry.getKey(), element.getAsString());
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // A malformed document must never stop the addon from starting.
        }
    }

    /** A cached classification: only the intent name is kept, so the cache stays tiny. */
    private record Cached(String intentName) {
    }
}
