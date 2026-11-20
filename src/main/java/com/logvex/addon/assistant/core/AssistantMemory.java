package com.logvex.addon.assistant.core;



import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The assistant's persistent memory: free-form facts, per-player facts, a bounded conversation
 * history and running counters.
 *
 * <p>Everything the assistant "knows" is either a fact the user stored or something the client
 * actually observed. Nothing here is inferred about the world, so the assistant can never claim to
 * remember something that was never recorded.
 *
 * <p>The class is pure data with explicit versioning. {@link #CURRENT_VERSION} is bumped whenever
 * the on-disk shape changes and {@link #migrate(Map)} upgrades older maps in place, so a stored
 * memory from an older build is upgraded rather than discarded.
 */
public final class AssistantMemory {
    /** On-disk schema version. Bump on any incompatible change and add a migration step. */
    public static final int CURRENT_VERSION = 1;

    /** Upper bound on stored conversation turns; the oldest are dropped first. */
    public static final int MAX_HISTORY = 200;

    /** One stored key/value fact. */
    public record Fact(String key, String value, long updatedAt) {
    }

    /** One remembered exchange: what was asked and what the assistant answered. */
    public record ConversationEntry(long timestamp, String player, String question, String answer) {
    }

    private int version = CURRENT_VERSION;
    private long updatedAt;

    private final Map<String, Fact> facts = new LinkedHashMap<>();
    private final Map<String, Map<String, Fact>> playerFacts = new LinkedHashMap<>();
    private final List<ConversationEntry> history = new ArrayList<>();
    private final Map<String, Long> counters = new LinkedHashMap<>();

    public int version() {
        return version;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public void touch() {
        updatedAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ facts

    /** Stores {@code key -> value}, replacing any previous value. Returns false for a blank key. */
    public boolean rememberFact(String key, String value, long now) {
        if (key == null || key.isBlank()) return false;
        String normalised = key.trim().toLowerCase(Locale.ROOT);
        facts.put(normalised, new Fact(normalised, value == null ? "" : value, now));
        updatedAt = now;
        return true;
    }

    public Optional<Fact> fact(String key) {
        if (key == null) return Optional.empty();
        return Optional.ofNullable(facts.get(key.trim().toLowerCase(Locale.ROOT)));
    }

    public boolean forgetFact(String key) {
        if (key == null) return false;
        return facts.remove(key.trim().toLowerCase(Locale.ROOT)) != null;
    }

    /** Every stored fact, in insertion order. */
    public List<Fact> facts() {
        return List.copyOf(facts.values());
    }

    /**
     * The stored fact whose key appears in {@code text}, if exactly one does.
     *
     * <p>This is what lets the assistant answer from its own memory instead of only from a fixed
     * corpus: "what is the prefix" reaches the {@code prefix} fact without a trained example for
     * that sentence. Matching is on whole words so {@code server} does not match {@code servers}
     * and {@code fact} does not match {@code factory}.
     *
     * <p>Nesting is not ambiguity. A store holding {@code spawn} and {@code spawn point} answers the
     * longer key when both match, because the longer key is the more specific reading of the same
     * words. Two <em>unrelated</em> keys matching, such as {@code server} and {@code spawn} in
     * "where is the server spawn", is genuine ambiguity and returns empty: a wrong fact is worse
     * than none, because the user cannot see that a guess happened.
     */
    public Optional<Fact> recall(String text) {
        if (text == null || text.isBlank() || facts.isEmpty()) return Optional.empty();
        // rawTokens, not tokens: tokens() drops stopwords and stems, which would erase the very
        // words a key is built from ("what is the prefix" must still reach the `prefix` key).
        java.util.Set<String> words = new java.util.HashSet<>(TextTokenizer.rawTokens(text));
        if (words.isEmpty()) return Optional.empty();

        List<Fact> matches = new ArrayList<>(2);
        for (Fact fact : facts.values()) {
            if (mentionsKey(words, fact.key())) matches.add(fact);
        }
        if (matches.isEmpty()) return Optional.empty();
        if (matches.size() == 1) return Optional.of(matches.get(0));

        // Keep only the most specific matches: drop any whose words are a strict subset of another
        // match. What survives is either one key (the nested case) or several unrelated ones.
        List<Fact> mostSpecific = new ArrayList<>(matches.size());
        for (Fact candidate : matches) {
            boolean shadowed = false;
            for (Fact other : matches) {
                if (other != candidate && isStrictSubset(candidate.key(), other.key())) {
                    shadowed = true;
                    break;
                }
            }
            if (!shadowed) mostSpecific.add(candidate);
        }
        return mostSpecific.size() == 1 ? Optional.of(mostSpecific.get(0)) : Optional.empty();
    }

    /** True when every word of {@code key} is present in {@code words}. */
    private static boolean mentionsKey(java.util.Set<String> words, String key) {
        if (key == null || key.isBlank()) return false;
        for (String part : TextTokenizer.rawTokens(key)) {
            if (!words.contains(part)) return false;
        }
        return true;
    }

    /** True when {@code narrower}'s words are all in {@code wider} and {@code wider} has more. */
    private static boolean isStrictSubset(String narrower, String wider) {
        List<String> narrow = TextTokenizer.rawTokens(narrower);
        List<String> wide = TextTokenizer.rawTokens(wider);
        return narrow.size() < wide.size() && wide.containsAll(narrow);
    }

    // ------------------------------------------------------------------ player facts

    /** Stores {@code key -> value} for one player. Returns false for a blank player or key. */
    public boolean rememberPlayerFact(String player, String key, String value, long now) {
        if (player == null || player.isBlank() || key == null || key.isBlank()) return false;
        String p = player.trim().toLowerCase(Locale.ROOT);
        String k = key.trim().toLowerCase(Locale.ROOT);
        playerFacts.computeIfAbsent(p, ignored -> new LinkedHashMap<>())
            .put(k, new Fact(k, value == null ? "" : value, now));
        updatedAt = now;
        return true;
    }

    public Optional<Fact> playerFact(String player, String key) {
        if (player == null || key == null) return Optional.empty();
        Map<String, Fact> perPlayer = playerFacts.get(player.trim().toLowerCase(Locale.ROOT));
        if (perPlayer == null) return Optional.empty();
        return Optional.ofNullable(perPlayer.get(key.trim().toLowerCase(Locale.ROOT)));
    }

    public boolean forgetPlayerFact(String player, String key) {
        if (player == null || key == null) return false;
        Map<String, Fact> perPlayer = playerFacts.get(player.trim().toLowerCase(Locale.ROOT));
        if (perPlayer == null) return false;
        boolean removed = perPlayer.remove(key.trim().toLowerCase(Locale.ROOT)) != null;
        if (perPlayer.isEmpty()) playerFacts.remove(player.trim().toLowerCase(Locale.ROOT));
        return removed;
    }

    /** Every fact stored for {@code player}, in insertion order. */
    public List<Fact> playerFacts(String player) {
        if (player == null) return List.of();
        Map<String, Fact> perPlayer = playerFacts.get(player.trim().toLowerCase(Locale.ROOT));
        return perPlayer == null ? List.of() : List.copyOf(perPlayer.values());
    }

    /** The players that have at least one stored fact, in insertion order. */
    public List<String> knownPlayers() {
        return List.copyOf(playerFacts.keySet());
    }

    public boolean forgetPlayer(String player) {
        if (player == null) return false;
        return playerFacts.remove(player.trim().toLowerCase(Locale.ROOT)) != null;
    }

    // ------------------------------------------------------------------ conversation history

    /** Appends a remembered exchange, trimming the oldest entries past {@link #MAX_HISTORY}. */
    public void recordConversation(String player, String question, String answer, long now) {
        history.add(new ConversationEntry(now, player == null ? "" : player,
            question == null ? "" : question, answer == null ? "" : answer));
        while (history.size() > MAX_HISTORY) history.remove(0);
        updatedAt = now;
    }

    /** The most recent {@code limit} exchanges, newest first. */
    public List<ConversationEntry> history(int limit) {
        List<ConversationEntry> out = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && out.size() < Math.max(0, limit); i--) {
            out.add(history.get(i));
        }
        return out;
    }

    public int historySize() {
        return history.size();
    }

    /** Every remembered exchange, oldest first. */
    public List<ConversationEntry> allHistory() {
        return List.copyOf(history);
    }

    public void clearHistory() {
        history.clear();
    }

    // ------------------------------------------------------------------ counters

    /** Adds {@code delta} to the named counter (creating it at zero first). */
    public long incrementCounter(String name, long delta) {
        if (name == null || name.isBlank()) return 0;
        String key = name.trim().toLowerCase(Locale.ROOT);
        long next = counters.getOrDefault(key, 0L) + delta;
        counters.put(key, next);
        return next;
    }

    public long counter(String name) {
        if (name == null) return 0;
        return counters.getOrDefault(name.trim().toLowerCase(Locale.ROOT), 0L);
    }

    public Map<String, Long> counters() {
        return java.util.Map.copyOf(counters);
    }

    // ------------------------------------------------------------------ lifecycle

    public boolean isEmpty() {
        return facts.isEmpty() && playerFacts.isEmpty() && history.isEmpty() && counters.isEmpty();
    }

    public void clear() {
        facts.clear();
        playerFacts.clear();
        history.clear();
        counters.clear();
        updatedAt = System.currentTimeMillis();
    }

    /**
     * Upgrades a raw stored map to {@link #CURRENT_VERSION}.
     *
     * @return true when the map was understood (or empty); false when it came from a newer build and
     *         must not be loaded, so a downgrade never destroys a newer file
     */
    public boolean migrate(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            this.version = CURRENT_VERSION;
            return true;
        }
        Object storedRaw = raw.get("version");
        int stored = storedRaw instanceof Number n ? n.intValue() : parseVersion(storedRaw);
        if (stored > CURRENT_VERSION) return false;
        this.version = CURRENT_VERSION;
        return true;
    }

    private static int parseVersion(Object value) {
        if (value == null) return CURRENT_VERSION;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return CURRENT_VERSION;
        }
    }

    // ------------------------------------------------------------------ copy

    /**
     * A deep, independent copy of this memory.
     *
     * <p>Used before handing the memory to the background writer: the live instance is mutated on
     * the client thread, so serialising it directly from the IO thread could observe a collection
     * mid-change. The snapshot is taken on the caller's thread, which owns the memory.
     */
    public AssistantMemory copy() {
        AssistantMemory copy = new AssistantMemory();
        copy.version = this.version;
        copy.updatedAt = this.updatedAt;
        copy.facts.putAll(this.facts);
        for (Map.Entry<String, Map<String, Fact>> entry : this.playerFacts.entrySet()) {
            copy.playerFacts.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        copy.history.addAll(this.history);
        copy.counters.putAll(this.counters);
        return copy;
    }

    public void setVersion(int value) {
        this.version = value;
    }

    public void setUpdatedAt(long value) {
        this.updatedAt = value;
    }
}
