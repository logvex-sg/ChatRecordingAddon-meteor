package com.logvex.addon.ai.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a chat line into tokens and n-grams.
 *
 * <p>Deliberately tiny and allocation-light: the assistant may classify every line a server sends,
 * so tokenisation must not be the expensive part. The pipeline is normalise, split, drop stopwords,
 * then optionally apply a very small stemmer that only trims the handful of English suffixes that
 * actually change intent words ("running" -> "run", "stacks" -> "stack").
 *
 * <p>No dictionary, no model file: everything here is arithmetic on the input string, so the whole
 * class costs a few kilobytes and microseconds per call.
 */
public final class TextTokenizer {
    /** Words too common to carry intent. Kept short on purpose. */
    private static final Set<String> STOPWORDS = Set.of(
        "a", "an", "the", "is", "are", "was", "were", "be", "been", "being", "am",
        "do", "does", "did", "of", "to", "in", "on", "at", "for", "and", "or", "but",
        "if", "then", "so", "as", "it", "its", "this", "that", "these", "those",
        "i", "you", "he", "she", "we", "they", "me", "him", "her", "us", "them",
        "my", "your", "his", "our", "their", "with", "from", "by", "about");

    private TextTokenizer() {
    }

    /** Lowercases and replaces every non-letter/digit run with a single space. */
    public static String normalise(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (pendingSpace && sb.length() > 0) sb.append(' ');
                pendingSpace = false;
                sb.append(Character.toLowerCase(c));
            } else {
                pendingSpace = true;
            }
        }
        return sb.toString();
    }

    /** Every token, stopwords included, in order. */
    public static List<String> rawTokens(String text) {
        String normalised = normalise(text);
        if (normalised.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(8);
        for (String word : normalised.split(" ")) {
            if (!word.isEmpty()) out.add(word);
        }
        return out;
    }

    /** Content tokens: stopwords removed, then lightly stemmed. */
    public static List<String> tokens(String text) {
        List<String> raw = rawTokens(text);
        if (raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(raw.size());
        for (String word : raw) {
            if (STOPWORDS.contains(word)) continue;
            out.add(stem(word));
        }
        return out;
    }

    /**
     * A conservative suffix trimmer.
     *
     * <p>It is not a real stemmer and does not try to be: it only folds the suffixes whose removal
     * reliably maps different surface forms of the same intent word onto one token. A word that
     * would become shorter than three characters is left alone, so "is" never becomes "i".
     */
    public static String stem(String word) {
        if (word == null || word.length() < 4) return word;
        String w = word;
        if (w.endsWith("ies") && w.length() > 4) return w.substring(0, w.length() - 3) + "y";
        if (w.endsWith("sses") || w.endsWith("shes") || w.endsWith("ches")) {
            return w.substring(0, w.length() - 2);
        }
        if (w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us")) {
            return w.substring(0, w.length() - 1);
        }
        if (w.endsWith("ing") && w.length() > 5) {
            String base = w.substring(0, w.length() - 3);
            if (base.length() >= 3) return dedupeEnd(base);
        }
        if (w.endsWith("ed") && w.length() > 4) {
            String base = w.substring(0, w.length() - 2);
            if (base.length() >= 3) return dedupeEnd(base);
        }
        return w;
    }

    /** "runn" -> "run", so the ing/ed trim of a doubled consonant lands on the real stem. */
    private static String dedupeEnd(String base) {
        int n = base.length();
        if (n >= 2 && base.charAt(n - 1) == base.charAt(n - 2)) {
            return base.substring(0, n - 1);
        }
        return base;
    }

    /** Word bigrams, which carry word-order signal a bag of words loses. */
    public static List<String> bigrams(List<String> tokens) {
        if (tokens == null || tokens.size() < 2) return List.of();
        List<String> out = new ArrayList<>(tokens.size() - 1);
        for (int i = 0; i + 1 < tokens.size(); i++) {
            out.add(tokens.get(i) + "_" + tokens.get(i + 1));
        }
        return out;
    }

    /** Cheap stable hash used for feature maps, avoiding String keys where a long will do. */
    public static long hash(String token) {
        long h = 1125899906842597L;
        for (int i = 0; i < token.length(); i++) h = 31 * h + token.charAt(i);
        return h;
    }

    /** A display form for a token, used in generated text: {@code oak_planks} -> {@code oak planks}. */
    public static String display(String token) {
        if (token == null) return "";
        return token.replace('_', ' ').toLowerCase(Locale.ROOT);
    }
}
