"""The answer renderer.

The box exists so an answer is scannable, so the tests are about the properties that make it
readable rather than about exact strings: every row the same width, no word silently dropped,
and a plain mode for terminals that cannot draw a box.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from assistant_bot import (  # noqa: E402
    DEFAULT_WIDTH,
    MAX_WIDTH,
    _fit_path,
    _wrap,
    make_assistant,
    render_embed,
)


class WrapTest(unittest.TestCase):
    def test_wrap_keeps_every_word(self):
        text = "the quick brown fox jumps over the lazy dog again and again"
        for width in (10, 20, 40):
            joined = " ".join(_wrap(text, width))
            self.assertEqual(joined, text, f"words were lost at width {width}")

    def test_wrap_never_exceeds_width(self):
        for width in (5, 12, 30):
            for line in _wrap("supercalifragilisticexpialidocious and more words here", width):
                self.assertLessEqual(len(line), width)

    def test_empty_text_is_one_blank_row(self):
        self.assertEqual(_wrap("", 20), [""])


class FitPathTest(unittest.TestCase):
    def test_short_path_is_untouched(self):
        self.assertEqual(_fit_path("src/A.java", 40), "src/A.java")

    def test_long_path_keeps_its_filename_and_fits(self):
        path = "src/main/java/com/logvex/addon/chatlog/modules/WebhookRelayModule.java"
        fitted = _fit_path(path, 30)
        self.assertLessEqual(len(fitted), 30)
        self.assertTrue(fitted.endswith("WebhookRelayModule.java"))


class RenderEmbedTest(unittest.TestCase):
    def test_every_row_is_the_same_width(self):
        block = render_embed("title", [("body", "some words that will wrap over a few rows"),
                                       ("kind", "setting")], footer="id")
        widths = {len(line) for line in block.split("\n")}
        self.assertEqual(len(widths), 1, f"ragged block: {widths}")

    def test_block_is_closed_at_the_top_and_bottom(self):
        lines = render_embed("t", [("k", "v")], footer="f").split("\n")
        self.assertTrue(lines[0].startswith("\u250c"))
        self.assertTrue(lines[-1].startswith("\u2514"))

    def test_custom_width_is_honoured(self):
        block = render_embed("t", [("k", "v")], width=64)
        self.assertTrue(all(len(line) == 64 for line in block.split("\n")))

    def test_very_long_title_is_shortened_not_overflowed(self):
        block = render_embed("x" * 200, [("k", "v")])
        for line in block.split("\n"):
            self.assertLessEqual(len(line), DEFAULT_WIDTH)

    def test_body_without_labels_uses_the_full_width(self):
        block = render_embed("t", [("", "a" * 30)])
        self.assertIn("a" * 30, block)

    def test_section_heading_is_rendered_as_a_rule(self):
        block = render_embed("t", [("related!", "a, b")])
        self.assertIn("related", block)
        self.assertIn("\u2500", block)


class AnswerShapeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.boxed = make_assistant()
        cls.plain = make_assistant(boxed=False)

    def test_boxed_answer_is_a_closed_block(self):
        text = self.boxed.answer("what does the webhook url setting do").text
        lines = text.split("\n")
        self.assertTrue(lines[0].startswith("\u250c"))
        self.assertTrue(lines[-1].startswith("\u2514"))
        self.assertEqual(len({len(line) for line in lines}), 1)

    def test_box_never_exceeds_the_cap(self):
        for question in ("what does the chat logger do", "what does the webhook url setting do"):
            text = self.boxed.answer(question).text
            for line in text.split("\n"):
                self.assertLessEqual(len(line), MAX_WIDTH)

    def test_boxed_answer_still_carries_the_source(self):
        answer = self.boxed.answer("what does the chat logger do")
        self.assertIn(answer.source, answer.text)

    def test_plain_answer_has_no_box_drawing(self):
        text = self.plain.answer("what does the chat logger do").text
        for char in "\u250c\u2510\u2514\u2518\u2502\u2500":
            self.assertNotIn(char, text)

    def test_plain_answer_keeps_the_facts(self):
        answer = self.plain.answer("what does the webhook url setting do")
        self.assertIn(answer.term, answer.text)
        self.assertIn(answer.source, answer.text)
        self.assertIn(answer.record_id, answer.text)

    def test_refusal_is_not_wrapped_in_a_box(self):
        answer = self.boxed.answer("what is the best pvp kit for skywars")
        self.assertFalse(answer.found)
        self.assertNotIn("\u250c", answer.text)

    def test_related_is_surfaced_for_settings(self):
        answer = self.boxed.answer("what does the webhook url setting do")
        self.assertTrue(answer.related)
        self.assertIn(answer.related[0], answer.text)


if __name__ == "__main__":
    unittest.main()
