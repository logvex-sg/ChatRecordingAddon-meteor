package com.logvex.addon.ai.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    private static final int MAX_INTENTS = 32;
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

    /**
     * Keyword rules tried before the classifier.
     *
     * <p>The classifier is good at a rough topic and bad at the fine distinctions that matter in
     * chat: measured on 58 ordinary paraphrases it answered 45, and several answers were confidently
     * wrong — "what is the prefix" read as serverinfo, "how are you" as tickrate. A bag of words
     * cannot separate those, because the distinguishing token is a stopword the tokenizer drops.
     *
     * <p>So a small ordered table of required-token sets runs first. A rule fires only when every
     * one of its tokens is present, which makes it precise: {@code ping} alone does not answer a
     * question about the server. First match wins, so the specific rules are listed before the broad
     * ones. Anything that falls through is classified as before, so this only ever adds accuracy.
     */
    private final List<Route> router;

    /**
     * Repairs one-letter slips against the words this model was actually trained on.
     *
     * <p>Built from the corpus rather than a dictionary, so it can only ever suggest a word the
     * classifier has evidence for. Rebuilt whenever a new example is taught.
     */
    private volatile TypoCorrector corrector;

    public IntentModel() {
        // 8192 buckets, not 2048: at 2048 a word and an unrelated bigram still shared a slot often
        // enough to matter — "night" was counted for the game-help class, which contains no such
        // word, and that class then answered "is it night". The corpus holds well over a thousand
        // distinct words and bigrams, so 8192 leaves several buckets per feature. The cost is
        // 32 x 8192 doubles, about 2 MB, constant and paid once at load.
        this.classifier = new NaiveBayesClassifier(8192, MAX_INTENTS);
        trainBuiltins();
        this.router = buildRouter();
        rebuildCorrector();
    }

    /** Rebuilds the typo vocabulary from every trained example. */
    private void rebuildCorrector() {
        Set<String> words = new HashSet<>();
        for (Intent intent : intents.values()) words.addAll(TypoCorrector.wordsOf(intent.examples()));
        for (List<String> examples : taught.values()) words.addAll(TypoCorrector.wordsOf(examples));
        this.corrector = new TypoCorrector(words);
    }

    /** The typo corrector, exposed so a test can assert it against the trained corpus. */
    public TypoCorrector corrector() {
        return corrector;
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
        Intent routed = route(text);
        if (routed == null) {
            // Route the repaired text too. "were am i" misses the "where am i" rule because the
            // typo is a different token, and the classifier alone then picks the wrong class.
            String repaired = correct(text);
            if (!repaired.equals(text)) routed = route(repaired);
        }
        if (routed != null) return routed;
        // Repair one-letter slips before classifying. A misspelt word is a feature the classifier
        // has never seen, so "helo" scores zero everywhere and the line goes unanswered even though
        // the meaning was plain. The router above runs on the raw tokens, because its rules are
        // short and its own matching is already tolerant.
        NaiveBayesClassifier.Result result = classifier.classifyWithConfidence(correct(text));
        if (!result.known() || result.confidence() < threshold) {
            // Still not confident: the correction may have been a coin flip between two real words
            // ("helo" is one edit from both "hello" and "help"). Try each reading and keep the one
            // the classifier is surest about, which is the reading that fits the rest of the line.
            NaiveBayesClassifier.Result best = resolveAmbiguous(text);
            if (best != null) result = best;
        }
        if (!result.known() || result.confidence() < threshold) return null;
        String name = classifier.className(result.index());
        return name == null ? null : intents.get(name);
    }

    /**
     * Classifies each reading of an ambiguous typo and returns the most confident one.
     *
     * <p>Returns null when there is nothing ambiguous to try. Bounded to a handful of variants so a
     * pathological line cannot cause work proportional to the vocabulary.
     */
    private NaiveBayesClassifier.Result resolveAmbiguous(String text) {
        TypoCorrector current = corrector;
        if (current == null) return null;
        List<String> variants = readings(text, current);
        if (variants.isEmpty()) return null;
        NaiveBayesClassifier.Result best = null;
        for (String variant : variants) {
            NaiveBayesClassifier.Result attempt = classifier.classifyWithConfidence(variant);
            if (!attempt.known()) continue;
            if (best == null || attempt.confidence() > best.confidence()) best = attempt;
        }
        return best == null ? null : best;
    }

    /** One variant per ambiguous word, with that word replaced by each of its candidates. */
    private static List<String> readings(String text, TypoCorrector corrector) {
        List<String> out = new ArrayList<>(2);
        List<String> words = TextTokenizer.rawTokens(text);
        for (String word : words) {
            List<String> candidates = corrector.candidates(word);
            if (candidates.isEmpty()) continue;
            for (String candidate : candidates) {
                out.add(replaceWord(text, word, candidate));
                if (out.size() >= 4) return out;
            }
        }
        return out;
    }

    /** Replaces whole-word occurrences of {@code from} with {@code to}, case-insensitively. */
    private static String replaceWord(String text, String from, String to) {
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
            String word = text.substring(start, i);
            out.append(word.equalsIgnoreCase(from) ? to : word);
        }
        return out.toString();
    }

    /** The text with unknown one-letter-away words repaired, or the input when nothing is unclear. */
    public String correct(String text) {
        TypoCorrector current = corrector;
        return current == null ? text : current.correct(text);
    }

    /**
     * The intent the keyword table selects for {@code text}, or null when no rule applies.
     *
     * <p>Matching uses {@link TextTokenizer#rawTokens}, not {@code tokens}: the stopwords that
     * {@code tokens} removes are exactly the words that distinguish these rules — {@code how are
     * you} from {@code what are you}, and {@code where am i} from {@code who am i}. Both sides are
     * stemmed so {@code coords} matches {@code coordinates}.
     *
     * <p>Exposed so a test can assert the routing table directly, and so a caller can tell a routed
     * answer from a classified one when diagnosing a bad reply.
     */
    public Intent route(String text) {
        if (text == null || text.isBlank()) return null;
        java.util.Set<String> tokens = stems(TextTokenizer.rawTokens(text));
        if (tokens.isEmpty()) return null;
        for (Route rule : router) {
            if (tokens.containsAll(rule.required())) {
                Intent intent = intents.get(rule.intent());
                if (intent != null) return intent;
            }
        }
        return null;
    }

    private static java.util.Set<String> stems(List<String> words) {
        java.util.Set<String> out = new java.util.HashSet<>(words.size() * 2);
        for (String word : words) out.add(TextTokenizer.stem(word));
        return out;
    }

    /**
     * One keyword rule: an intent plus the tokens that must all be present.
     *
     * @param required every token here must appear in the message
     */
    private record Route(String intent, java.util.Set<String> required) {
    }

    private static Route route(String intent, String... required) {
        return new Route(intent, stems(List.of(required)));
    }

    /**
     * The routing table, most specific first.
     *
     * <p>Every rule here exists because the classifier got that phrasing wrong, so the table is
     * evidence-driven rather than exhaustive. Order matters where rules overlap:
     * <ul>
     *   <li>{@code server lag} must beat bare {@code server}, which would answer a lag question with
     *       the server's name.</li>
     *   <li>{@code what can you do} must beat {@code what ... you}, which is the identity rule.</li>
     *   <li>{@code how are you} must beat {@code you there}, which is the same intent anyway but
     *       would otherwise decide by accident.</li>
     * </ul>
     */
    private static List<Route> buildRouter() {
        return List.of(
            route("prefix", "prefix"),
            // Before the "how do i" help rule below, which would otherwise swallow every how-to.
            route("gamehelp", "claim", "land"),
            route("gamehelp", "beacon"),
            route("gamehelp", "village"),
            route("gamehelp", "diamond"),
            route("gamehelp", "craft"),
            route("gamehelp", "enchant"),
            route("gamehelp", "best", "sword"),
            route("gamehelp", "best", "tool"),
            route("help", "how", "use"),
            route("help", "how", "work"),
            route("help", "what", "can", "you", "do"),
            route("help", "what", "command"),
            route("help", "help"),
            // Memory.
            route("memory", "what", "remember"),
            route("memory", "what", "know"),
            // Status. These use stopwords, which is why the router reads raw tokens.
            route("status", "how", "are", "you"),
            route("status", "how", "you", "doing"),
            route("status", "how", "is", "it", "going"),
            route("status", "you", "ok"),
            route("status", "you", "there"),
            route("status", "whats", "up"),
            route("status", "you", "good"),
            route("status", "how", "r", "u"),
            route("status", "how", "u", "doin"),
            // Position. Before the gamehelp rules: "wheres the base" is a position question, and
            // the village rule below would otherwise claim it because both are places.
            route("position", "where", "am", "i"),
            route("position", "wheres", "base"),
            route("position", "where", "base"),
            route("position", "coords"),
            route("position", "biome"),
            // Player count and who is around. Added because the classifier answered "how many
            // players" with the tick rate and "whos online" with its own identity: with no
            // examples for this topic it picked whichever class shared the most words.
            route("players", "how", "many", "player"),
            route("players", "player", "count"),
            route("players", "how", "many", "ppl"),
            route("players", "how", "many", "people"),
            route("players", "who", "online"),
            route("players", "whos", "online"),
            route("players", "whose", "online"),
            route("players", "whos", "on"),
            route("players", "anyone", "online"),
            route("players", "anybody", "online"),
            route("players", "online", "now"),
            route("players", "who", "here"),
            // Tick rate before serverinfo: "is the server lagging" names the server but asks about lag.
            route("tickrate", "server", "lag"),
            route("tickrate", "tps"),
            // "tickrate" is one word, so the two-token rule below never matched it. Both spellings
            // are in common use and only the spaced one was covered.
            route("tickrate", "tickrate"),
            route("tickrate", "tick", "rate"),
            route("tickrate", "lag"),
            route("latency", "ping"),
            route("latency", "latency"),
            route("latency", "laggy"),
            route("serverinfo", "server"),
            route("serverinfo", "dimension"),
            route("serverinfo", "world"),
            route("weather", "weather"),
            route("weather", "raining"),
            route("weather", "rain"),
            route("weather", "sunny"),
            route("weather", "night"),
            route("weather", "day", "time"),
            route("time", "time"),
            route("time", "clock"),
            route("time", "date"),
            route("time", "today"),
            route("joke", "joke"),
            route("joke", "funny"),
            route("joke", "laugh"),
            route("compliment", "good", "bot"),
            route("compliment", "nice", "bot"),
            route("compliment", "good", "job"),
            route("compliment", "well", "done"),
            route("insult", "bad", "bot"),
            route("insult", "stupid", "bot"),
            route("identity", "are", "you", "bot"),
            route("identity", "are", "you", "human"),
            route("identity", "who", "you"),
            route("identity", "what", "you"),
            route("identity", "your", "name"),
            route("thanks", "thanks"),
            route("thanks", "thank", "you"),
            route("thanks", "ty"),
            route("thanks", "thx"),
            route("thanks", "tyvm"),
            route("thanks", "appreciate"),
            route("thanks", "cheers"),
            route("farewell", "bye"),
            route("farewell", "goodbye"),
            route("farewell", "cya"),
            route("farewell", "gtg"),
            route("farewell", "later"),
            route("farewell", "im", "out"),
            route("farewell", "good", "night"),
            route("serverinfo", "ip"),
            route("congrats", "gg"),
            route("congrats", "wp"),
            // After the "nice bot" compliment rule above, so praise of the bot still wins.
            route("congrats", "nice"),
            route("congrats", "ez"),
            route("laughter", "lol"),
            route("laughter", "lmao"),
            route("laughter", "haha"),
            route("greeting", "hello"),
            route("greeting", "hi"),
            route("greeting", "hey"),
            route("greeting", "yo"),
            route("greeting", "sup"),
            route("greeting", "hiya"),
            route("greeting", "howdy"),
            route("greeting", "morning"),
            route("greeting", "evening")
        );
    }

    /** The confidence of the best match, for diagnostics and tests. */
    public double confidence(String text) {
        NaiveBayesClassifier.Result result = classifier.classifyWithConfidence(text);
        return result.known() ? result.confidence() : 0;
    }

    /**
     * Classifies {@code text} with the keyword table bypassed.
     *
     * <p>Diagnostics only. It exists so the classifier's own accuracy can be measured apart from the
     * router — the comparison is what justifies the router existing, and a claim about it should be
     * reproducible rather than asserted.
     */
    public Intent classifyWithoutRouting(String text) {
        NaiveBayesClassifier.Result result = classifier.classifyWithConfidence(text);
        if (!result.known() || result.confidence() < threshold) return null;
        String name = classifier.className(result.index());
        return name == null ? null : intents.get(name);
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
            rebuildCorrector();
            return true;
        }
        int count = exampleCounts.getOrDefault(intentName, existing.examples().size());
        if (count >= MAX_EXAMPLES_PER_INTENT) return false;
        classifier.train(intentName, example);
        generator.train(example);
        exampleCounts.put(intentName, count + 1);
        taught.computeIfAbsent(intentName, key -> new ArrayList<>()).add(example);
        rebuildCorrector();
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
        train(new Intent("prefix",
            List.of("what is the prefix", "whats the prefix", "the prefix",
                "how do i start a command", "command prefix", "what symbol",
                "how do i talk to you", "what do i type"),
            List.of("The prefix is {prefix} \u2014 try {prefix}help.",
                "Start a command with {prefix}, as in {prefix}facts.",
                "My commands begin with {prefix}.")));
        train(new Intent("status",
            List.of("how are you", "how are you doing", "how is it going", "you good",
                "you there", "are you there", "whats up", "how do you feel",
                "everything ok", "you ok"),
            List.of("Running well, {player}.", "All good here.", "Present and listening.",
                "Fine, thanks for asking.")));
        train(new Intent("congrats",
            List.of("gg", "wp", "good game", "well played", "nice one", "congrats",
                "congratulations", "nice", "ez"),
            List.of("Nice one!", "Well played.", "Good game.", "Clean.")));
        train(new Intent("laughter",
            List.of("lol", "lmao", "haha", "hehe", "rofl", "hah", "lmfao", "thats funny"),
            List.of("Ha.", "Glad you liked it.", "Heh.", "Fair.")));
        train(new Intent("time",
            List.of("what time is it", "the time", "current time", "time now",
                "what is the time", "tell me the time"),
            List.of("It is {time}.", "The clock says {time}.")));
        train(new Intent("serverinfo",
            List.of("what server is this", "which server", "server name", "what is the server",
                "what world is this", "which dimension", "what dimension is this"),
            List.of("This is {server}, on {dimension}.", "{server} \u2014 {dimension}.",
                "You are on {server} in {world}.")));
        train(new Intent("latency",
            List.of("what is my ping", "how is my ping", "my latency", "how laggy",
                "is it lagging", "how is the connection", "connection quality"),
            List.of("Your ping is {ping} ms \u2014 {verdict}.", "{ping} ms, {verdict}.",
                "Latency {ping} ms ({verdict}).")));
        train(new Intent("tickrate",
            List.of("tps", "tickrate", "tick rate", "server tps", "whats the tps",
                "how many tps", "is the server lagging", "how fast is the server",
                "how many ticks per second", "server lag"),
            List.of("TPS is {tps}/20 at {mspt} ms per tick.", "{tps}/20, {mspt} ms per tick.",
                "The server is running at {tps}/20.")));
        train(new Intent("position",
            List.of("where am i", "what are my coordinates", "my coords", "what is my position",
                "which biome is this", "what biome"),
            List.of("You are at {coords} in {biome}.", "{coords}, {biome}, facing {facing}.",
                "Coordinates {coords}.")));
        // Game questions the corpus had no topic for. Without these the classifier answered
        // "how do i claim land" with nothing at all, because no class shared its words.
        //
        // The examples deliberately omit the question scaffolding ("how do i", "where is the",
        // "what is the best"). Those words appear in most other intents, so including them made
        // this class claim "how are you" and "where even am i" — a broad class that swallows its
        // neighbours is worse than the gap it fills. Only the domain nouns are listed.
        train(new Intent("gamehelp",
            List.of("claim land", "beacon recipe", "village nearby", "best sword",
                "best pickaxe", "get diamonds", "make a farm", "enchant a sword"),
            List.of("I cannot play the game for you, {player}, but ask {prefix}help for what I can do.",
                "That is a game question I have no data on. I only know this client's chat and stats.",
                "I have no recipe data. Try the wiki, or ask me for coords, ping or players.")));
        // Domain nouns only, for the same reason as gamehelp: "who are you" and "how are you"
        // both contain who/how/are/you, so examples built from those words made this class
        // answer the identity and status questions. The router covers the phrasings.
        train(new Intent("players",
            List.of("player count", "players online", "how many players", "how many ppl",
                "how many people online", "players in the tab", "tab list", "online players",
                "population", "server population"),
            List.of("There are {players} players online.", "{players} online right now.",
                "{players} players in the tab list.")));
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
            List.of("It is {weather} here, {player}.", "The sky says {weather}.",
                "Weather: {weather}.")));
        train(new Intent("identity",
            List.of("who are you", "what are you", "your name", "what is your name",
                "are you a bot", "are you human", "who is this"),
            List.of("I am {name}, a local assistant running in the client.",
                "{name}, at your service. I run entirely on your machine.")));
    }
}
