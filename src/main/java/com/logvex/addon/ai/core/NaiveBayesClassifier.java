package com.logvex.addon.ai.core;

import java.util.List;

/**
 * A multinomial naive-Bayes intent classifier over hashed features.
 *
 * <p>Why this and not a neural network: the addon must classify chat lines on the game thread and
 * stay under a percent of a CPU core, ship no model file, and be unit-testable with no runtime
 * dependency. A naive-Bayes model over a hashed bag of words and bigrams is a few kilobytes, trains
 * in microseconds from a handful of example phrases, and is good enough for the small closed set of
 * intents a chat assistant needs.
 *
 * <p>Features are hashed into a fixed number of buckets, so memory is constant no matter how many
 * examples or how long a message is: there is no vocabulary that can grow without bound. Collisions
 * between unrelated tokens cost a little accuracy and cannot cause a leak.
 *
 * <p>Training is incremental, which is what lets a user teach the assistant a new phrasing at
 * runtime without rebuilding anything.
 */
public final class NaiveBayesClassifier {
    /** Weight of a word feature relative to a bigram feature. */
    private static final double WORD_WEIGHT = 1.0;
    private static final double BIGRAM_WEIGHT = 0.5;
    /** Laplace smoothing, so an unseen feature is improbable rather than impossible. */
    private static final double ALPHA = 0.5;
    /**
     * Softmax temperature applied to the per-feature average score.
     *
     * <p>Chosen so a per-feature gap of about a tenth of a nat — the typical separation between the
     * right intent and the runner-up on a short chat line — becomes a decisive probability, while a
     * near-tie stays under the model's threshold. Lower makes the classifier more conservative.
     */
    private static final double TEMPERATURE = 0.12;
    /**
     * Cap on distinct trained features remembered for the "seen at all" gate.
     *
     * <p>Hashed features can collide, so a word that was never trained can land in a bucket that
     * another class happens to have counted. Storing the exact set of trained feature hashes lets a
     * classification reject a message whose every feature is unknown, which is what stops a random
     * nonsense word from being answered with high confidence. The set is bounded so it cannot grow
     * without limit; once full, new features are simply not remembered.
     */
    private static final int MAX_SEEN_FEATURES = 20_000;

    private final int buckets;
    private final int maxClasses;
    private final String[] classNames;
    private final double[][] counts;
    private final double[] totals;
    private final double[] documents;
    private final java.util.Set<Long> seenFeatures = new java.util.HashSet<>();
    private int classCount;

    public NaiveBayesClassifier(int buckets, int maxClasses) {
        this.buckets = Math.max(64, buckets);
        this.maxClasses = Math.max(2, maxClasses);
        this.classNames = new String[this.maxClasses];
        this.counts = new double[this.maxClasses][this.buckets];
        this.totals = new double[this.maxClasses];
        this.documents = new double[this.maxClasses];
    }

    public int bucketCount() {
        return buckets;
    }

    public int classCount() {
        return classCount;
    }

    public String className(int index) {
        return index >= 0 && index < classCount ? classNames[index] : null;
    }

    public String[] classNames() {
        return java.util.Arrays.copyOf(classNames, classCount);
    }

    /** Index of {@code name}, or -1 when unknown. */
    public int indexOf(String name) {
        if (name == null) return -1;
        for (int i = 0; i < classCount; i++) {
            if (name.equals(classNames[i])) return i;
        }
        return -1;
    }

    /** Registers a class, returning its index, or -1 when the model is full. */
    public int addClass(String name) {
        int existing = indexOf(name);
        if (existing >= 0) return existing;
        if (classCount >= maxClasses) return -1;
        classNames[classCount] = name;
        return classCount++;
    }

    /** Trains one example for {@code className}, creating the class if needed. */
    public boolean train(String className, String text) {
        int index = addClass(className);
        if (index < 0) return false;
        return train(index, text);
    }

    /** Trains one example for an existing class index. */
    public boolean train(int classIndex, String text) {
        if (classIndex < 0 || classIndex >= classCount) return false;
        List<String> tokens = TextTokenizer.tokens(text);
        if (tokens.isEmpty()) return false;

        double[] row = counts[classIndex];
        double sum = 0;
        for (String token : tokens) {
            row[bucket(token)] += WORD_WEIGHT;
            sum += WORD_WEIGHT;
            remember(token);
        }
        for (String bigram : TextTokenizer.bigrams(tokens)) {
            row[bucket(bigram)] += BIGRAM_WEIGHT;
            sum += BIGRAM_WEIGHT;
            remember(bigram);
        }
        totals[classIndex] += sum;
        documents[classIndex] += 1;
        return true;
    }

    private void remember(String feature) {
        if (seenFeatures.size() < MAX_SEEN_FEATURES) seenFeatures.add(TextTokenizer.hash(feature));
    }

    /** The class index with the highest score, or -1 when the model is empty. */
    public int classify(String text) {
        return classifyWithConfidence(text).index();
    }

    /**
     * Classifies and reports a confidence in {@code [0, 1]}.
     *
     * <p>Confidence is a softmax over the <em>average</em> log-likelihood per feature, not the raw
     * sum. The raw sum grows with message length, so a long message and a two-word message would
     * otherwise be scored on different scales and a short message could never look decisive. Dividing
     * by the feature count puts every message on one scale; the temperature then maps the typical
     * per-feature gap between a correct and an incorrect intent (a few tenths of a nat) onto a
     * decisive probability, while a genuine near-tie stays below the caller's threshold.
     */
    public Result classifyWithConfidence(String text) {
        if (classCount == 0) return new Result(-1, 0, new double[0]);
        List<String> tokens = TextTokenizer.tokens(text);
        if (tokens.isEmpty()) return new Result(-1, 0, new double[0]);

        double[] scores = new double[classCount];
        int[] featureBuckets = featureBuckets(tokens);
        double totalDocuments = 0;
        for (int c = 0; c < classCount; c++) totalDocuments += documents[c];
        if (totalDocuments <= 0) return new Result(-1, 0, new double[0]);

        // Reject a message built entirely from features the model has never seen: with hashing, a
        // bucket collision can otherwise make a nonsense word look like a confident match.
        boolean anyKnown = false;
        for (String token : tokens) {
            if (seenFeatures.contains(TextTokenizer.hash(token))) {
                anyKnown = true;
                break;
            }
        }
        if (!anyKnown) {
            for (String bigram : TextTokenizer.bigrams(tokens)) {
                if (seenFeatures.contains(TextTokenizer.hash(bigram))) {
                    anyKnown = true;
                    break;
                }
            }
        }
        if (!anyKnown) return new Result(-1, 0, new double[0]);

        for (int c = 0; c < classCount; c++) {
            double prior = Math.log((documents[c] + ALPHA) / (totalDocuments + ALPHA * classCount));
            double denominator = totals[c] + ALPHA * buckets;
            double score = prior;
            double[] row = counts[c];
            for (int b : featureBuckets) {
                score += Math.log((row[b] + ALPHA) / denominator);
            }
            scores[c] = score / featureBuckets.length;
        }

        int best = 0;
        for (int c = 1; c < classCount; c++) {
            if (scores[c] > scores[best]) best = c;
        }
        double max = scores[best];
        double sum = 0;
        for (int c = 0; c < classCount; c++) {
            scores[c] = Math.exp((scores[c] - max) / TEMPERATURE);
            sum += scores[c];
        }
        double confidence = sum > 0 ? scores[best] / sum : 0;
        return new Result(best, confidence, scores);
    }

    private int[] featureBuckets(List<String> tokens) {
        List<String> bigrams = TextTokenizer.bigrams(tokens);
        int[] out = new int[tokens.size() + bigrams.size()];
        int i = 0;
        for (String token : tokens) out[i++] = bucket(token);
        for (String bigram : bigrams) out[i++] = bucket(bigram);
        return out;
    }

    private int bucket(String feature) {
        long h = TextTokenizer.hash(feature);
        return (int) ((h & 0x7fffffffL) % buckets);
    }

    /** A classification outcome: the winning class, its confidence and the raw softmax vector. */
    public record Result(int index, double confidence, double[] distribution) {
        public boolean known() {
            return index >= 0;
        }

        public double confidenceOf(int classIndex) {
            return classIndex >= 0 && classIndex < distribution.length ? distribution[classIndex] : 0;
        }
    }
}
