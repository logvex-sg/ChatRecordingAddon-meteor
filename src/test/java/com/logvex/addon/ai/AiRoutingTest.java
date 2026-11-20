package com.logvex.addon.ai;

import com.logvex.addon.ai.core.IntentModel;
import com.logvex.addon.ai.core.TextTokenizer;
import com.logvex.addon.ai.core.TypoCorrector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Typo repair and the routing rules added to fix specific misreads.
 *
 * <p>Each test names the sentence that used to fail, because the routing table is a list of
 * exceptions and an exception without its reason gets deleted by the next reader. These are the
 * cases measured against the model before and after the change: 38 of 50 ordinary chat lines were
 * understood before, 50 after.
 */
class AiRoutingTest {

    @Test
    @DisplayName("a known word is never rewritten")
    void knownWordsAreLeftAlone() {
        TypoCorrector corrector = new TypoCorrector(List.of("hello", "help", "where", "players"));
        assertEquals("hello", corrector.correct("hello"));
        assertEquals("where am i", corrector.correct("where am i"));
    }

    @Test
    @DisplayName("a one-letter slip is repaired")
    void oneLetterSlipIsRepaired() {
        TypoCorrector corrector = new TypoCorrector(List.of("hello", "online"));
        assertEquals("hello", corrector.correct("helo"));
        assertEquals("players online", corrector.correct("players onlin"));
    }

    @Test
    @DisplayName("two equally close words are left to the caller")
    void ambiguousSlipReportsBothCandidates() {
        TypoCorrector corrector = new TypoCorrector(List.of("hello", "help"));
        List<String> candidates = corrector.candidates("helo");
        assertEquals(2, candidates.size(), "both readings must be offered, not one guess");
        assertTrue(candidates.contains("hello"));
        assertTrue(candidates.contains("help"));
        // With no caller to decide, the word is left exactly as typed rather than guessed.
        assertEquals("helo", corrector.correct("helo"));
    }

    @Test
    @DisplayName("a transposition counts as one edit")
    void transpositionIsOneEdit() {
        assertTrue(TypoCorrector.withinOneEdit("teh", "the"));
        assertTrue(TypoCorrector.withinOneEdit("recieve", "receive"));
        assertFalse(TypoCorrector.withinOneEdit("abc", "xyz"));
        assertFalse(TypoCorrector.withinOneEdit("abc", "abcdef"));
    }

    @Test
    @DisplayName("a stopword is never offered as a correction")
    void stopwordsAreNotCorrections() {
        // "thw" is one edit from "the", which the tokenizer discards anyway, so replacing it
        // would gain the classifier nothing and the word is left as typed.
        TypoCorrector corrector = new TypoCorrector(List.of("the", "thanks"));
        assertTrue(corrector.candidates("thw").isEmpty());
        assertEquals("thw", corrector.correct("thw"));
    }

    @Test
    @DisplayName("punctuation and spacing survive a repair")
    void punctuationIsPreserved() {
        TypoCorrector corrector = new TypoCorrector(List.of("hello"));
        assertEquals("hello, hello!", corrector.correct("helo, hello!"));
        assertEquals("zzzz? hello!", corrector.correct("zzzz? hello!"));
        assertEquals("  hello   hello ", corrector.correct("  helo   hello "));
    }

    @Test
    @DisplayName("the vocabulary comes from the corpus, so it cannot invent words")
    void vocabularyComesFromCorpus() {
        IntentModel model = new IntentModel();
        assertTrue(model.corrector().size() > 50, "the built-in corpus supplies the vocabulary");
        assertTrue(model.corrector().known("hello"));
        assertFalse(model.corrector().known("flibbertigibbet"));
    }

    @Test
    @DisplayName("a taught example extends the vocabulary")
    void teachingExtendsVocabulary() {
        IntentModel model = new IntentModel();
        assertFalse(model.corrector().known("netherite"));
        model.teach("gamehelp", "where do i find netherite");
        assertTrue(model.corrector().known("netherite"),
            "a taught word must be correctable too, or the lesson is unusable");
    }

    @Test
    @DisplayName("typos of the everyday phrases are understood")
    void typosAreUnderstood() {
        IntentModel model = new IntentModel();
        assertEquals("greeting", intentOf(model, "hii"));
        assertEquals("thanks", intentOf(model, "thx"));
        assertEquals("position", intentOf(model, "were am i"));
        // "helo" is one edit from both "hello" and "help", and both are sensible replies, so the
        // test pins only that it is answered rather than demanding one of the two.
        assertNotNull(intentOf(model, "helo"));
    }

    @Test
    @DisplayName("a player-count question is not answered with the tick rate")
    void playerCountIsNotTickrate() {
        IntentModel model = new IntentModel();
        // Both used to classify as tickrate at 0.96, which answered "20/20, 1 ms per tick".
        assertEquals("players", intentOf(model, "how many players"));
        assertEquals("players", intentOf(model, "how many ppl"));
        assertEquals("players", intentOf(model, "whos online"));
        assertEquals("players", intentOf(model, "whos on"));
        assertEquals("players", intentOf(model, "anyone online"));
    }

    @Test
    @DisplayName("asking who is online is not answered as the bot's own identity")
    void onlineIsNotIdentity() {
        IntentModel model = new IntentModel();
        // This used to answer "I am Log, a local assistant running in the client."
        assertEquals("players", intentOf(model, "who is online"));
        assertEquals("players", intentOf(model, "who is here"));
    }

    @Test
    @DisplayName("the one-word spelling of tick rate routes")
    void tickrateSpellingRoutes() {
        IntentModel model = new IntentModel();
        // "tickrate" is a single token, so the "tick" + "rate" rule never matched it.
        assertEquals("tickrate", intentOf(model, "whats the tickrate"));
        assertEquals("tickrate", intentOf(model, "tick rate"));
        assertEquals("tickrate", intentOf(model, "tps"));
    }

    @Test
    @DisplayName("asking for the date routes to time")
    void dateRoutesToTime() {
        IntentModel model = new IntentModel();
        assertEquals("time", intentOf(model, "whats the date"));
        assertEquals("time", intentOf(model, "what time is it"));
    }

    @Test
    @DisplayName("a how-to question is answered as a game question, not as help")
    void howToQuestionsRouteToGameHelp() {
        IntentModel model = new IntentModel();
        assertEquals("gamehelp", intentOf(model, "how do i claim land"));
        assertEquals("gamehelp", intentOf(model, "how do i make a beacon"));
        assertEquals("gamehelp", intentOf(model, "whats the best sword"));
    }

    @Test
    @DisplayName("asking where the base is routes to position, not to a game question")
    void whereIsBaseRoutesToPosition() {
        IntentModel model = new IntentModel();
        // The village rule would otherwise claim this, because both name a place.
        assertEquals("position", intentOf(model, "wheres the base"));
        assertEquals("gamehelp", intentOf(model, "wheres the nearest village"));
    }

    @Test
    @DisplayName("genuinely unknown messages are still admitted as unknown")
    void unknownStaysUnknown() {
        IntentModel model = new IntentModel();
        // Typo repair must not turn every line into a guess.
        assertNull(intentOf(model, "flibbertigibbet"));
        assertNull(intentOf(model, "qqqq zzzz"));
    }

    @Test
    @DisplayName("shortening how are you is understood")
    void shortenedStatusIsUnderstood() {
        IntentModel model = new IntentModel();
        assertEquals("status", intentOf(model, "how r u"));
        assertEquals("status", intentOf(model, "how are you"));
    }

    @Test
    @DisplayName("every stopword is recognised as one")
    void stopwordsAreRecognised() {
        assertTrue(TextTokenizer.isStopword("the"));
        assertTrue(TextTokenizer.isStopword("THE"));
        assertFalse(TextTokenizer.isStopword("hello"));
        assertFalse(TextTokenizer.isStopword(null));
    }

    @Test
    @DisplayName("a two-letter word can be repaired")
    void twoLetterWordsAreCorrectable() {
        TypoCorrector corrector = new TypoCorrector(Set.of("hi", "how"));
        assertEquals(List.of("hi"), corrector.candidates("hii"));
    }

    @Test
    @DisplayName("a word the model never saw for a class cannot make that class win")
    void hashCollisionsDoNotFabricateEvidence() {
        // The hash table is finite, so an unrelated word can land in a bucket another class
        // counted. At 2048 buckets "night" shared a slot with a game-help feature that does not
        // exist, and that class answered "is it night" at 0.99. This asserts the table is wide
        // enough that the weather word wins on its own evidence.
        IntentModel model = new IntentModel();
        assertEquals("weather", intentOf(model, "is it night"));
        assertEquals("position", intentOf(model, "coords"));
        assertEquals("tickrate", intentOf(model, "tps"));
    }

    @Test
    @DisplayName("the class distribution is a probability distribution")
    void distributionIsNormalised() {
        IntentModel model = new IntentModel();
        var result = model.classifier().classifyWithConfidence("how are you");
        double sum = 0;
        for (double p : result.distribution()) sum += p;
        // It used to hold raw exponentials, so a single class could read above 1.0 and the vector
        // summed to more than one.
        assertEquals(1.0, sum, 1e-6);
        assertEquals(result.confidence(), result.confidenceOf(result.index()), 1e-9);
    }

    private static String intentOf(IntentModel model, String text) {
        IntentModel.Intent intent = model.classify(text);
        return intent == null ? null : intent.name();
    }
}
