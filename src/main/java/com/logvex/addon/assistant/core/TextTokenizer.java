package com.logvex.addon.assistant.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a chat line into lowercase word tokens.
 *
 * <p>Used by the memory store to compare a fact key against a question, so it only needs to be
 * cheap and predictable. No dictionary and no model: everything here is arithmetic on the input
 * string.
 */
public final class TextTokenizer {
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
}
