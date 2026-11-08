package com.logvex.addon.ai;

import com.google.gson.JsonObject;
import com.logvex.addon.ai.core.AiEngine;
import com.logvex.addon.ai.core.BigramModel;
import com.logvex.addon.ai.core.IntentModel;
import com.logvex.addon.ai.core.NaiveBayesClassifier;
import com.logvex.addon.ai.core.TextTokenizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The local AI: tokenisation, classification, generation and persistence.
 *
 * <p>These tests pin the properties the feature depends on rather than exact sentences, so the
 * built-in corpus can be edited without rewriting the suite: a greeting must classify as a
 * greeting, an unknown message must be admitted as unknown, and a saved model must reload with the
 * same understanding.
 */
class AiCoreTest {

    // ------------------------------------------------------------------ tokenizer

    @Test
    @DisplayName("normalise lowercases and splits on punctuation")
    void normaliseSplits() {
        assertEquals("hello there friend", TextTokenizer.normalise("Hello, there!! friend?"));
        assertEquals("", TextTokenizer.normalise(null));
        assertEquals("", TextTokenizer.normalise("   "));
    }

    @Test
    @DisplayName("tokens drop stopwords and fold plural and gerund forms")
    void tokensStem() {
        assertEquals(List.of("run"), TextTokenizer.tokens("running"));
        assertEquals(List.of("stack"), TextTokenizer.tokens("stacks"));
        assertEquals(List.of("city"), TextTokenizer.tokens("cities"));
        // "is" is a stopword and never reaches the stemmer, so it cannot become "i".
        assertEquals(List.of(), TextTokenizer.tokens("is a the"));
    }

    @Test
    @DisplayName("bigrams join adjacent tokens in order")
    void bigramsJoin() {
        assertEquals(List.of("hello_there", "there_friend"),
            TextTokenizer.bigrams(List.of("hello", "there", "friend")));
        assertEquals(List.of(), TextTokenizer.bigrams(List.of("solo")));
    }

    // ------------------------------------------------------------------ classifier

    @Test
    @DisplayName("a classifier separates two intents trained on a few examples")
    void classifierSeparates() {
        NaiveBayesClassifier model = new NaiveBayesClassifier(128, 4);
        model.train("greeting", "hello there");
        model.train("greeting", "hi how are you");
        model.train("farewell", "goodbye see you later");
        model.train("farewell", "bye now");

        assertEquals("greeting", model.className(model.classify("hello")));
        assertEquals("farewell", model.className(model.classify("goodbye")));
    }

    @Test
    @DisplayName("an empty model classifies nothing rather than guessing")
    void emptyModelKnowsNothing() {
        NaiveBayesClassifier model = new NaiveBayesClassifier(64, 4);
        assertFalse(model.classifyWithConfidence("hello").known());
        assertEquals(-1, model.classify("hello"));
    }

    @Test
    @DisplayName("the model refuses more classes than its cap instead of growing without bound")
    void classCapEnforced() {
        NaiveBayesClassifier model = new NaiveBayesClassifier(64, 3);
        assertTrue(model.addClass("a") >= 0);
        assertTrue(model.addClass("b") >= 0);
        assertTrue(model.addClass("c") >= 0);
        assertEquals(-1, model.addClass("d"));
        assertEquals(3, model.classCount());
    }

    // ------------------------------------------------------------------ generator

    @Test
    @DisplayName("the generator produces the same sentence for the same seed")
    void generatorDeterministic() {
        BigramModel model = new BigramModel();
        for (int i = 0; i < 10; i++) model.train("the quick brown fox jumps over the lazy dog");
        assertEquals(model.generate(42L, 8), model.generate(42L, 8));
        assertFalse(model.generate(42L, 8).isBlank());
    }

    @Test
    @DisplayName("an untrained generator says nothing rather than inventing text")
    void generatorEmpty() {
        BigramModel model = new BigramModel();
        assertEquals("", model.generate(1L, 8));
    }

    @Test
    @DisplayName("the generator respects its word cap")
    void generatorWordCap() {
        BigramModel model = new BigramModel();
        for (int i = 0; i < 5; i++) model.train("one two three four five six seven eight nine ten");
        String text = model.generate(7L, 4);
        assertTrue(text.split(" ").length <= 4, "expected at most 4 words, got: " + text);
    }

    // ------------------------------------------------------------------ intents

    @Test
    @DisplayName("the built-in corpus understands greetings, thanks and questions about itself")
    void builtinIntents() {
        IntentModel model = new IntentModel();
        assertEquals("greeting", nameOf(model, "hello there"));
        assertEquals("greeting", nameOf(model, "hey"));
        assertEquals("thanks", nameOf(model, "thank you so much"));
        assertEquals("identity", nameOf(model, "who are you"));
        assertEquals("help", nameOf(model, "what can you do"));
        assertTrue(model.intentCount() >= 10);
    }

    @Test
    @DisplayName("nonsense is not forced onto an intent")
    void unknownIsAdmitted() {
        IntentModel model = new IntentModel();
        assertNull(model.classify("xylophone quokka zzzzz"));
    }

    @Test
    @DisplayName("a reply is always on-topic and never empty")
    void replyOnTopic() {
        IntentModel model = new IntentModel();
        IntentModel.Intent greeting = model.intent("greeting");
        assertNotNull(greeting);
        for (long seed = 0; seed < 20; seed++) {
            String reply = model.reply(greeting, seed, 120);
            assertFalse(reply.isBlank(), "seed " + seed + " produced no reply");
            assertTrue(reply.length() <= 120);
        }
    }

    @Test
    @DisplayName("runtime teaching makes a new phrasing understood")
    void teachWorks() {
        IntentModel model = new IntentModel();
        assertNull(model.classify("flibbertigibbet"));
        model.teach("greeting", "flibbertigibbet");
        assertEquals("greeting", nameOf(model, "flibbertigibbet"));
    }

    private static String nameOf(IntentModel model, String text) {
        IntentModel.Intent intent = model.classify(text);
        return intent == null ? null : intent.name();
    }

    // ------------------------------------------------------------------ engine

    @Test
    @DisplayName("the engine answers a greeting and fills in the player name")
    void engineAnswers() {
        AiEngine engine = new AiEngine();
        String reply = engine.respond("hello there", "Notch", Map.of(), 5L);
        assertFalse(reply.isBlank());
        assertTrue(reply.contains("Notch") || !reply.contains("{player}"),
            "placeholder left unfilled: " + reply);
        assertEquals(1, engine.classified());
        assertEquals(1, engine.understood());
    }

    @Test
    @DisplayName("the engine stays silent on a message it does not understand")
    void engineSilentWhenUnknown() {
        AiEngine engine = new AiEngine();
        assertEquals("", engine.respond("xylophone quokka zzzzz", "Notch", Map.of(), 1L));
        assertEquals(0, engine.understood());
    }

    @Test
    @DisplayName("a repeated line is served from the cache and not reclassified")
    void engineCaches() {
        AiEngine engine = new AiEngine();
        engine.respond("hello there", "Notch", Map.of(), 1L);
        engine.respond("hello there", "Notch", Map.of(), 2L);
        assertEquals(1, engine.classified());
        assertEquals(1, engine.cacheHits());
    }

    @Test
    @DisplayName("a disabled engine says nothing at all")
    void engineDisabled() {
        AiEngine engine = new AiEngine();
        engine.setEnabled(false);
        assertEquals("", engine.respond("hello there", "Notch", Map.of(), 1L));
        assertEquals(0, engine.classified());
    }

    @Test
    @DisplayName("context values are substituted into a reply")
    void engineContext() {
        String filled = AiEngine.fill("It is {time}, {player}.", "Notch", Map.of("time", "noon"));
        assertEquals("It is noon, Notch.", filled);
    }

    // ------------------------------------------------------------------ persistence

    @Test
    @DisplayName("a saved model reloads with the same understanding and tunables")
    void persistenceRoundTrip() {
        AiEngine engine = new AiEngine();
        engine.setLearnFromChat(true);
        engine.setMaxReplyLength(90);
        engine.model().teach("greeting", "flibbertigibbet");
        JsonObject saved = engine.toJson();

        AiEngine reloaded = new AiEngine();
        reloaded.loadJson(saved);
        assertTrue(reloaded.learnFromChat());
        assertEquals(90, reloaded.maxReplyLength());
        assertEquals("greeting", reloaded.intentOf("flibbertigibbet"));
    }

    @Test
    @DisplayName("a document from a newer build is refused rather than half-applied")
    void newerDocumentRefused() {
        AiEngine engine = new AiEngine();
        JsonObject future = new JsonObject();
        future.addProperty("version", AiEngine.STORE_VERSION + 5);
        future.addProperty("learnFromChat", true);
        engine.loadJson(future);
        assertFalse(engine.learnFromChat());
    }

    @Test
    @DisplayName("a malformed document is ignored and the built-ins survive")
    void malformedDocumentIgnored() {
        AiEngine engine = new AiEngine();
        JsonObject junk = new JsonObject();
        junk.addProperty("version", AiEngine.STORE_VERSION);
        junk.addProperty("threshold", "not a number");
        engine.loadJson(junk);
        assertEquals("greeting", engine.intentOf("hello there"));
    }
}
