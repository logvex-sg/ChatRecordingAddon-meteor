package com.logvex.addon.ai.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fixes the single-letter slips that stop a short chat line from being understood.
 *
 * <p>People type "helo", "hii", "were am i", "hw are you". A bag-of-words classifier sees those as
 * words it has never met, so the line scores nothing and the assistant stays silent even though the
 * meaning was obvious. This repairs one token at a time against the words the model already knows.
 *
 * <p>It is deliberately timid, because a wrong "correction" is worse than no answer:
 * <ul>
 *   <li>only tokens the model does <em>not</em> know are touched, so a real word is never rewritten;</li>
 *   <li>the fix must be exactly one edit away (one insert, delete, substitute or swap);</li>
 *   <li>if two known words are equally close, nothing is changed — a coin flip is not a correction;</li>
 *   <li>numbers and tokens shorter than three characters are left alone.</li>
 * </ul>
 *
 * <p>Pure text processing with no Minecraft dependency, so it can be tested directly.
 */
public final class TypoCorrector {
    /**
     * Shortest word kept in the vocabulary, and shortest input worth correcting.
     *
     * <p>Two, not three: the corpus answers "hi", "ty" and "yo", and a typo of one of those
     * ("hii", "tjy") is exactly the kind of slip this class exists to repair. A two-letter input
     * is only ever rewritten when exactly one known word is one edit away, so the risk of
     * mangling a real abbreviation stays small.
     */
    private static final int MIN_LENGTH = 2;
    /** Most alternatives to hand back for one word; enough to be ambiguous, cheap to classify. */
    private static final int MAX_CANDIDATES = 3;

    private final Set<String> vocabulary;

    public TypoCorrector(Collection<String> words) {
        Set<String> set = new HashSet<>();
        if (words != null) {
            for (String word : words) {
                if (word == null) continue;
                String clean = word.trim().toLowerCase(Locale.ROOT);
                if (clean.length() >= MIN_LENGTH) set.add(clean);
            }
        }
        this.vocabulary = Set.copyOf(set);
    }

    /** True when {@code word} is a word the model was trained on. */
    public boolean known(String word) {
        return word != null && vocabulary.contains(word.trim().toLowerCase(Locale.ROOT));
    }

    public int size() {
        return vocabulary.size();
    }

    /**
     * Rewrites the unknown words in {@code text}, leaving everything else exactly as typed.
     *
     * <p>Punctuation is preserved: only the letter runs between separators are considered, so the
     * repaired line keeps the shape the caller will display.
     */
    public String correct(String text) {
        if (text == null || text.isEmpty() || vocabulary.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                out.append(c);
                i++;
                continue;
            }
            int start = i;
            while (i < text.length() && Character.isLetterOrDigit(text.charAt(i))) i++;
            out.append(fix(text.substring(start, i)));
        }
        return out.toString();
    }

    /** The repaired form of one word, or the word itself when no confident fix exists. */
    String fix(String word) {
        List<String> candidates = candidates(word);
        return candidates.size() == 1 ? candidates.get(0) : word;
    }

    /**
     * The words {@code word} could plausibly be, nearest first.
     *
     * <p>An empty list means the word is already known or too short to touch. More than one entry
     * means the correction is genuinely ambiguous — "helo" is one edit from both "hello" and
     * "help" — and the caller decides, because it has the classifier and this class does not.
     */
    public List<String> candidates(String word) {
        String lower = word == null ? "" : word.toLowerCase(Locale.ROOT);
        if (lower.length() < MIN_LENGTH || vocabulary.contains(lower)) return List.of();
        if (lower.chars().allMatch(Character::isDigit)) return List.of();

        List<String> found = new ArrayList<>(2);
        for (String candidate : vocabulary) {
            if (Math.abs(candidate.length() - lower.length()) > 1) continue;
            // A shared first letter is what keeps "were" from becoming "here": both are one edit
            // away, but only one of them is the word the sentence was reaching for.
            if (candidate.charAt(0) != lower.charAt(0)) continue;
            // A stopword is dropped by the tokenizer, so replacing a word with one would gain the
            // classifier nothing: "thx" is one edit from "the", and "the" carries no intent.
            if (TextTokenizer.isStopword(candidate)) continue;
            if (!withinOneEdit(lower, candidate)) continue;
            found.add(candidate);
            if (found.size() > MAX_CANDIDATES) break;
        }
        return found;
    }

    /** True when the edit distance between {@code a} and {@code b} is exactly one. */
    public static boolean withinOneEdit(String a, String b) {
        int la = a.length();
        int lb = b.length();
        if (Math.abs(la - lb) > 1) return false;
        if (la == lb) {
            int firstDiff = -1;
            for (int i = 0; i < la; i++) {
                if (a.charAt(i) == b.charAt(i)) continue;
                if (firstDiff >= 0) {
                    // A second difference is only allowed when the two are adjacent and swapped,
                    // which is the transposition people make most often ("teh", "recieve").
                    return firstDiff == i - 1
                        && a.charAt(firstDiff) == b.charAt(i)
                        && a.charAt(i) == b.charAt(firstDiff);
                }
                firstDiff = i;
            }
            return firstDiff >= 0;
        }
        String longer = la > lb ? a : b;
        String shorter = la > lb ? b : a;
        int i = 0;
        int j = 0;
        boolean skipped = false;
        while (i < longer.length() && j < shorter.length()) {
            if (longer.charAt(i) == shorter.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (skipped) return false;
            skipped = true;
            i++;
        }
        return true;
    }

    /** Every word in a list of example phrases, for building a vocabulary from a corpus. */
    public static Set<String> wordsOf(List<String> phrases) {
        Set<String> out = new HashSet<>();
        if (phrases == null) return out;
        for (String phrase : phrases) {
            if (phrase == null) continue;
            for (String word : TextTokenizer.rawTokens(phrase)) out.add(word);
        }
        return out;
    }
}
