package com.logvex.addon.ai.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The whole local model: an intent classifier plus a small text generator, trained from a built-in
 * seed corpus and whatever the user teaches at runtime.
 *
 * <p>The built-in corpus is what makes the assistant useful with no setup. Each {@link Intent} pairs
 * a name with example phrasings and a set of reply templates; training turns the phrasings into
 * classifier examples and the replies into generator material. Everything is plain text in this
 * class, so the model is inspectable and extensible without a toolchain.
 *
 * <p>The model is small on purpose. It is trained once at construction from a fixed corpus (a few
 * hundred short strings) and then only classified, which is the cheap path. Runtime teaching is
 * supported but bounded: a class can only be added while the classifier has room.
 */
public final class IntentModel {
    /** Confidence below which the assistant admits it does not understand. */
    public static final double DEFAULT_THRESHOLD = 0.45;
    private static final int MAX_INTENTS = 24;
    private static final int MAX_EXAMPLES_PER_INTENT = 40;

    /**
     * One intent: a name, the phrasings that should trigger it, and the replies to choose from.
     *
     * @param name     a stable id, e.g. {@code greeting}
     * @param examples short user phrasings, at least one word each
     * @param replies  reply templates; {@code {player}} and {@code {topic}} are substituted
     */
    public record Intent(String name, List<String> examples, List<String> replies) {
    }

    private final NaiveBayesClassifier classifier;
    private final BigramModel generator = new BigramModel();
    private final Map<String, Intent> intents = new LinkedHashMap<>();
    private final Map<String, Integer> exampleCounts = new LinkedHashMap<>();
    /** Examples added at runtime, kept so they can be persisted and replayed on load. */
    private final Map<String, List<String>> taught = new LinkedHashMap<>();
    private double threshold = DEFAULT_THRESHOLD;

    public IntentModel() {
        this.classifier = new NaiveBayesClassifier(512, MAX_INTENTS);
        trainBuiltins();
    }

    public double threshold() {
        return threshold;
    }

    public void setThreshold(double threshold) {
        this.threshold = Math.max(0.05, Math.min(0.95, threshold));
    }

    public int intentCount() {
        return intents.size();
    }

    public NaiveBayesClassifier classifier() {
        return classifier;
    }

    public BigramModel generator() {
        return generator;
    }

    public Intent intent(String name) {
        return name == null ? null : intents.get(name);
    }

    public List<String> intentNames() {
        return List.copyOf(intents.keySet());
    }

    /**
     * Classifies {@code text} and returns the best intent when it clears the confidence threshold.
     *
     * @return the matching intent, or null when the message is not understood well enough
     */
    public Intent classify(String text) {
        NaiveBayesClassifier.Result result = classifier.classifyWithConfidence(text);
        if (!result.known() || result.confidence() < threshold) return null;
        String name = classifier.className(result.index());
        return name == null ? null : intents.get(name);
    }

    /** The confidence of the best match, for diagnostics and tests. */
    public double confidence(String text) {
        NaiveBayesClassifier.Result result = classifier.classifyWithConfidence(text);
        return result.known() ? result.confidence() : 0;
    }

    /**
     * Chooses a reply for {@code intent}.
     *
     * <p>The template list is the first choice, so a reply is always on-topic. When the generator has
     * learned enough material, a generated sentence is offered instead, so repeated triggers do not
     * produce the identical line every time. The choice is deterministic for a given {@code seed}.
     */
    public String reply(Intent intent, long seed, int maxLength) {
        if (intent == null || intent.replies().isEmpty()) return "";
        java.util.Random random = new java.util.Random(seed);
        boolean useGenerated = generator.trainedLines() >= 4 && random.nextInt(4) == 0;
        if (useGenerated) {
            String generated = generator.generate(seed, 12);
            if (!generated.isBlank()) return clamp(generated, maxLength);
        }
        String template = intent.replies().get(random.nextInt(intent.replies().size()));
        return clamp(template, maxLength);
    }

    /** Trains one extra example for a known intent. Returns false when it cannot be added. */
    public boolean teach(String intentName, String example) {
        if (intentName == null || example == null || example.isBlank()) return false;
        Intent existing = intents.get(intentName);
        if (existing == null) {
            if (intents.size() >= MAX_INTENTS) return false;
            List<String> examples = new ArrayList<>();
            examples.add(example);
            intents.put(intentName, new Intent(intentName, List.copyOf(examples), List.of()));
            classifier.train(intentName, example);
            generator.train(example);
            exampleCounts.put(intentName, 1);
            taught.computeIfAbsent(intentName, key -> new ArrayList<>()).add(example);
            return true;
        }
        int count = exampleCounts.getOrDefault(intentName, existing.examples().size());
        if (count >= MAX_EXAMPLES_PER_INTENT) return false;
        classifier.train(intentName, example);
        generator.train(example);
        exampleCounts.put(intentName, count + 1);
        taught.computeIfAbsent(intentName, key -> new ArrayList<>()).add(example);
        return true;
    }

    /** Adds a reply template to a known intent, feeding the generator too. */
    public boolean addReply(String intentName, String reply) {
        Intent intent = intents.get(intentName);
        if (intent == null || reply == null || reply.isBlank()) return false;
        List<String> replies = new ArrayList<>(intent.replies());
        replies.add(reply);
        intents.put(intentName, new Intent(intentName, intent.examples(), List.copyOf(replies)));
        generator.train(reply);
        return true;
    }

    /** How many examples each intent has been trained on. */
    public Map<String, Integer> exampleCounts() {
        return Map.copyOf(exampleCounts);
    }

    /** Examples added at runtime, grouped by intent. Persisted and replayed on load. */
    public Map<String, List<String>> taughtExamples() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : taught.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return copy;
    }

    /** Replays a stored example during load, without re-recording it as newly taught. */
    public void replay(String intentName, String example) {
        if (intentName == null || example == null || example.isBlank()) return;
        if (intents.size() >= MAX_INTENTS && intents.get(intentName) == null) return;
        if (intents.get(intentName) == null) {
            intents.put(intentName, new Intent(intentName, List.of(example), List.of()));
        }
        classifier.train(intentName, example);
        generator.train(example);
        exampleCounts.merge(intentName, 1, Integer::sum);
    }

    private void train(Intent intent) {
        intents.put(intent.name(), intent);
        int count = 0;
        for (String example : intent.examples()) {
            if (count++ >= MAX_EXAMPLES_PER_INTENT) break;
            classifier.train(intent.name(), example);
            generator.train(example);
        }
        for (String reply : intent.replies()) generator.train(reply);
        exampleCounts.put(intent.name(), count);
    }

    private static String clamp(String text, int maxLength) {
        if (text == null) return "";
        String trimmed = text.strip();
        if (maxLength <= 0 || trimmed.length() <= maxLength) return trimmed;
        return trimmed.substring(0, Math.max(0, maxLength - 1)).stripTrailing() + "\u2026";
    }

    /**
     * The built-in corpus.
     *
     * <p>Kept in one place and intentionally small: a dozen intents, each with enough phrasings that
     * the classifier can separate them. Adding a phrasing here is the supported way to make the
     * assistant understand more without any user setup.
     */
    private void trainBuiltins() {
        train(new Intent("greeting",
            List.of("hello", "hi", "hey", "good morning", "good evening", "hi there",
                "hello there", "hey bot", "yo", "greetings", "howdy"),
            List.of("Hello {player}!", "Hi {player}.", "Hey there, {player}.")));
        train(new Intent("farewell",
            List.of("bye", "goodbye", "see you", "see you later", "cya", "farewell",
                "good night", "gtg", "i have to go"),
            List.of("See you, {player}.", "Goodbye {player}.", "Take care, {player}.")));
        train(new Intent("thanks",
            List.of("thanks", "thank you", "ty", "thanks a lot", "much appreciated",
                "cheers", "thank you so much"),
            List.of("You are welcome, {player}.", "Any time, {player}.", "Glad to help.")));
        train(new Intent("help",
            List.of("help", "can you help", "i need help", "how do i", "how to",
                "what can you do", "help me please", "commands", "what are your commands"),
            List.of("Try {prefix}remember key = value, {prefix}fact key, {prefix}help.",
                "I remember facts and answer keywords. Ask me {prefix}memory.",
                "Use {prefix}memory for a summary, {prefix}facts to list what I know.")));
        train(new Intent("status",
            List.of("status", "how are you", "are you ok", "are you there", "ping",
                "you there", "what is your status", "are you online"),
            List.of("Online and recording, {player}.", "All good here.", "Present.")));
        train(new Intent("time",
            List.of("what time is it", "the time", "current time", "time now",
                "what is the time", "tell me the time"),
            List.of("It is {time}.", "The clock says {time}.")));
        train(new Intent("memory",
            List.of("what do you remember", "what do you know", "your memory",
                "list facts", "what facts", "remember anything", "tell me what you know"),
            List.of("I know {factcount} facts.", "I have {factcount} facts stored.",
                "Ask {prefix}facts to see them.")));
        train(new Intent("affirm",
            List.of("yes", "yeah", "yep", "correct", "right", "exactly", "sure",
                "indeed", "that is right"),
            List.of("Understood.", "Noted.", "Got it.")));
        train(new Intent("deny",
            List.of("no", "nope", "nah", "wrong", "not really", "negative", "incorrect"),
            List.of("Fair enough.", "Okay.", "Understood.")));
        train(new Intent("compliment",
            List.of("good bot", "nice bot", "well done", "good job", "you are great",
                "i like you", "awesome", "you are the best", "nice work"),
            List.of("Thank you, {player}!", "Appreciated.", "Glad you like it.")));
        train(new Intent("insult",
            List.of("bad bot", "stupid bot", "you are dumb", "shut up", "you suck",
                "useless bot", "you are bad"),
            List.of("Noted.", "I will try to do better.", "Understood.")));
        train(new Intent("joke",
            List.of("tell me a joke", "joke", "say something funny", "make me laugh",
                "know any jokes", "funny"),
            List.of("Why did the creeper cross the road? To blow up the other side.",
                "I would tell a mining joke, but it is too deep.",
                "My inventory is full of puns.")));
        train(new Intent("weather",
            List.of("weather", "is it raining", "what is the weather", "is it sunny",
                "is it night", "raining outside"),
            List.of("Look outside, {player} \u2014 I only see chat.", "I cannot see the sky from here.")));
        train(new Intent("identity",
            List.of("who are you", "what are you", "your name", "what is your name",
                "are you a bot", "are you human", "who is this"),
            List.of("I am {name}, a local assistant running in the client.",
                "{name}, at your service. I run entirely on your machine.")));
    }
}
