package com.logvex.addon.ai.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A bounded bigram language model used to generate short chat replies.
 *
 * <p>It learns "what word tends to follow this word" from the assistant's example replies and can
 * then produce a novel sentence in the same style. This is the classic Markov-chain text generator,
 * chosen because it is the smallest thing that produces varied output rather than a fixed string,
 * trains instantly, and needs no floating-point matrix.
 *
 * <p>Both the vocabulary and the per-word successor lists are capped, so a chatty server cannot grow
 * the model without bound: once a cap is hit, new material is ignored rather than accumulated.
 */
public final class BigramModel {
    /** Hard ceiling on distinct words remembered. */
    private final int maxVocabulary;
    /** Hard ceiling on successors kept per word. */
    private final int maxSuccessors;

    private final Map<String, List<String>> successors = new HashMap<>();
    private final List<String> starts = new ArrayList<>();
    private int trainedLines;

    public BigramModel() {
        this(600, 6);
    }

    public BigramModel(int maxVocabulary, int maxSuccessors) {
        this.maxVocabulary = Math.max(16, maxVocabulary);
        this.maxSuccessors = Math.max(1, maxSuccessors);
    }

    public int vocabulary() {
        return successors.size();
    }

    public int trainedLines() {
        return trainedLines;
    }

    /** Learns one example sentence. Longer lines are truncated so one line cannot dominate. */
    public void train(String line) {
        List<String> words = TextTokenizer.rawTokens(line);
        if (words.size() < 2) return;
        if (words.size() > 24) words = words.subList(0, 24);

        if (successors.size() >= maxVocabulary && !successors.containsKey(words.get(0))) {
            return;
        }
        if (starts.size() < 64) starts.add(words.get(0));

        for (int i = 0; i + 1 < words.size(); i++) {
            String word = words.get(i);
            List<String> next = successors.get(word);
            if (next == null) {
                if (successors.size() >= maxVocabulary) continue;
                next = new ArrayList<>(4);
                successors.put(word, next);
            }
            if (next.size() < maxSuccessors) next.add(words.get(i + 1));
        }
        trainedLines++;
    }

    /**
     * Generates a sentence of at most {@code maxWords} words.
     *
     * <p>Deterministic for a given {@code seed}, which is what makes it testable: the same seed and
     * the same trained data always produce the same sentence. Returns an empty string when the model
     * has nothing to say.
     */
    public String generate(long seed, int maxWords) {
        if (starts.isEmpty() || successors.isEmpty()) return "";
        int limit = Math.max(2, maxWords);
        java.util.Random random = new java.util.Random(seed);

        String word = starts.get(random.nextInt(starts.size()));
        StringBuilder sb = new StringBuilder(word);
        int words = 1;
        while (words < limit) {
            List<String> next = successors.get(word);
            if (next == null || next.isEmpty()) break;
            word = next.get(random.nextInt(next.size()));
            sb.append(' ').append(word);
            words++;
        }
        return sb.toString();
    }

    /** Clears the learned material, keeping the configured caps. */
    public void clear() {
        successors.clear();
        starts.clear();
        trainedLines = 0;
    }
}
