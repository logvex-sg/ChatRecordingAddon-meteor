#!/usr/bin/env python3
"""A small offline assistant that answers questions about Log Client.

It is a retrieval bot, not a language model: it searches the generated datasets, returns the
best matching record, and says so when nothing matches. That makes it honest by construction —
it cannot invent a setting that does not exist — and it runs with no API key and no network.

    python3 tools/assistant_bot.py                    # interactive
    python3 tools/assistant_bot.py --ask "what does chat-logger do?"
    python3 tools/assistant_bot.py --stats
    python3 tools/assistant_bot.py --preview-telemetry

Anonymous usage counting is off until you turn it on with --opt-in. See TELEMETRY.md; the short
version is that it records which public record ids were answered and how many questions went
unanswered. It never records the text of a question.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
import time
from collections import Counter
from dataclasses import dataclass, field
from typing import Any, Iterable, Optional

HERE = os.path.dirname(os.path.abspath(__file__))
DATASET_DIR = os.path.join(HERE, "datasets")
STATE_DIR = os.path.join(
    os.environ.get("XDG_DATA_HOME") or os.path.expanduser("~/.local/share"), "logclient")
STATE_PATH = os.path.join(STATE_DIR, "usage.json")

TELEMETRY_SCHEMA = 1

# Words that carry no meaning when matching a question to a record.
STOPWORDS = {
    "a", "an", "the", "is", "are", "was", "were", "do", "does", "did", "what", "which", "who",
    "how", "why", "when", "where", "can", "could", "should", "would", "i", "my", "me", "you",
    "your", "it", "its", "this", "that", "these", "those", "for", "of", "to", "in", "on", "at",
    "by", "with", "and", "or", "not", "no", "yes", "if", "then", "there", "here", "be", "been",
    "have", "has", "had", "will", "just", "about", "from", "as", "so", "use", "using", "want",
    "need", "tell", "explain", "please", "help", "client",
}

# A match below this BM25 score is treated as "I do not know" rather than guessing.
CONFIDENCE_FLOOR = 5.0

# Fraction of the query's content words that must appear in the record. Without this, one
# incidental word ("skywars" against a setting that mentions "sky") is enough to answer.
MIN_QUERY_COVERAGE = 0.5

# A single matched word is not evidence. "what is my server ip 1.2.3.4" shares only "server" with
# the corpus, which would read as perfect coverage of one word. An exact name match is exempt,
# because "webhook-relay" is one word and unambiguous.
MIN_MATCHED_TOKENS = 2

# If this share of a question's words are absent from the corpus entirely, the question is out of
# scope. "who won the world cup" is 75% unknown, so answering from "world" would be a guess.
MAX_UNKNOWN_RATIO = 0.5

# Tokens that are data rather than vocabulary: hostnames, IPs, versions. A user pasting
# "does steve from play.example.com get alerts" is not asking about words the corpus lacks, so
# these are ignored when deciding whether a question is in scope.
IDENTIFIER_RE = re.compile(r"[.\d]")

# Bonus per query word that appears in a record's own name. A word in the name is a far stronger
# signal than the same word buried in prose: "search" is in the name of the `log search` command,
# while chat-logger only mentions searching in its description.
NAME_HIT_BONUS = 4.0

# Boost for a module record when the question covers at least half of the module's own name.
MODULE_AFFINITY_BONUS = 6.0

# Field weights. A hit in the term matters more than a hit in the prose.
FIELD_WEIGHTS = {"term": 3.0, "alias": 2.5, "module": 1.5, "definition": 1.0, "kind": 0.5}


# --------------------------------------------------------------------------- text


TOKEN_RE = re.compile(r"[a-z0-9][a-z0-9_.-]*")


def tokenize(text: str) -> list[str]:
    """Split into lowercase search tokens.

    Dots and hyphens are kept because setting names like ``webhook-url`` and ids like
    ``api/v1/chat`` are meaningful as single tokens; splitting them would make every part match
    far too much.
    """
    return TOKEN_RE.findall((text or "").lower())


def content_tokens(text: str) -> list[str]:
    return [token for token in tokenize(text) if token not in STOPWORDS and len(token) > 1]


COMPOUND_RE = re.compile(r"[-./]")


def expand(token: str) -> list[str]:
    """Index a compound name both whole and in parts.

    Setting and module names use hyphens (``chat-logger``) while people type the words apart
    ("chat logger"). Indexing only the compound means those questions never match, so both forms
    are indexed.
    """
    if not COMPOUND_RE.search(token):
        return [token]
    parts = [part for part in COMPOUND_RE.split(token) if len(part) > 1]
    return [token] + parts


def phrase_parts(text: str) -> list[str]:
    """Words of a name, with compounds split and the compound form dropped.

    Used only to compare a question against a record's name. index_tokens keeps both the
    compound ("chat-logger") and its parts, which is right for scoring but means "chat logger"
    and "chat-logger" would still compare unequal as strings.
    """
    out: list[str] = []
    for token in content_tokens(text):
        pieces = expand(token)
        if len(pieces) > 1:
            pieces = pieces[1:]
        for piece in pieces:
            stemmed = stem(piece)
            if stemmed not in STOPWORDS:
                out.append(stemmed)
    return out


def index_tokens(text: str) -> list[str]:
    """Tokens for the index: content tokens, split into parts, then stemmed."""
    out: list[str] = []
    for token in content_tokens(text):
        for piece in expand(token):
            stemmed = stem(piece)
            if stemmed not in STOPWORDS:
                out.append(stemmed)
    return out


def stem(token: str) -> str:
    """A very small stemmer: enough to match 'alerts' to 'alert' and 'recording' to 'record'."""
    for suffix in ("ing", "ed", "es", "s"):
        if len(token) >= len(suffix) + 3 and token.endswith(suffix):
            stripped = token[: -len(suffix)]
            if len(stripped) >= 3:
                return stripped
    return token


# --------------------------------------------------------------------------- index


@dataclass
class Document:
    record_id: str
    kind: str
    term: str
    definition: str
    source: str
    module: str = ""
    fields: dict[str, list[str]] = field(default_factory=dict)

    @property
    def all_tokens(self) -> list[str]:
        return [token for tokens in self.fields.values() for token in tokens]


class Index:
    """A BM25 index over the dictionary dataset.

    BM25 rather than plain keyword overlap because the records vary a lot in length, and a long
    definition would otherwise win every query just by containing more words.
    """

    def __init__(self, documents: list[Document], k1: float = 1.4, b: float = 0.72) -> None:
        self.documents = documents
        self.k1 = k1
        self.b = b
        self.doc_freq: Counter[str] = Counter()
        self.doc_lengths: list[int] = []
        for document in documents:
            tokens = set(document.all_tokens)
            self.doc_freq.update(tokens)
            self.doc_lengths.append(len(document.all_tokens))
        self.total = len(documents)
        self.average_length = (sum(self.doc_lengths) / self.total) if self.total else 0.0

    def idf(self, token: str) -> float:
        frequency = self.doc_freq.get(token, 0)
        if frequency == 0:
            return 0.0
        return math.log(1.0 + (self.total - frequency + 0.5) / (frequency + 0.5))

    def distinguishing_parts(self, document: Document) -> set[str]:
        """The words that identify this record, not its parent.

        A setting is named ``module.setting``, so its name always contains every word of its
        module's name. Comparing full names lets any setting outrank its own module on every
        query, which is a structural bug rather than a tuning problem. Stripping the module
        prefix compares like with like.
        """
        parts = phrase_parts(document.term)
        if document.module and document.kind == "setting":
            prefix = phrase_parts(document.module)
            if parts[:len(prefix)] == prefix:
                parts = parts[len(prefix):]
        return set(parts)

    def name_bonus(self, query: str, document: Document) -> float:
        """Reward query words that appear in the record's own name rather than its prose."""
        wanted = set(phrase_parts(query))
        if not wanted:
            return 0.0
        return NAME_HIT_BONUS * len(wanted & self.distinguishing_parts(document))

    def module_affinity(self, query: str, document: Document) -> float:
        """Boost a module when the question is about the module as a whole.

        "how do I drop a schematic" names the schematic-dropper module, but the words appear in
        several of its settings too. Rewarding the module for covering most of its own name
        resolves that without a blanket multiplier.
        """
        if document.kind != "module":
            return 0.0
        name = set(phrase_parts(document.term))
        asked = set(phrase_parts(query))
        if not name or not asked:
            return 0.0
        # The question must be about the module, not merely mention one of its words. Requiring
        # both directions stops "who won the world cup" from reaching world-intelligence via
        # "world", while still letting "show me the timeline" reach session-timeline.
        if len(name & asked) / len(name) < 0.5:
            return 0.0
        if len(name & asked) / len(asked) < 0.5:
            return 0.0
        return MODULE_AFFINITY_BONUS

    def phrase_bonus(self, query: str, document: Document) -> float:
        """Reward a record whose name is the query, not merely a word inside it.

        Without this, "chat logger" ranks the setting chat-logger.public-chat above the module
        chat-logger, because every query word appears in both.
        """
        wanted = " ".join(phrase_parts(query))
        if not wanted:
            return 0.0
        name = " ".join(phrase_parts(document.term))
        if not name:
            return 0.0
        if name == wanted:
            return 8.0
        if name.startswith(wanted) or wanted.startswith(name):
            return 3.0
        return 0.0

    def score(self, query_tokens: list[str], position: int) -> float:
        document = self.documents[position]
        length = self.doc_lengths[position] or 1
        total = 0.0
        for token in query_tokens:
            weight = 0.0
            for name, tokens in document.fields.items():
                count = tokens.count(token)
                if count:
                    weight = max(weight, FIELD_WEIGHTS.get(name, 1.0))
            if weight == 0.0:
                continue
            frequency = sum(tokens.count(token) for tokens in document.fields.values())
            if frequency == 0:
                continue
            denominator = frequency + self.k1 * (1 - self.b + self.b * length / self.average_length)
            total += self.idf(token) * weight * (frequency * (self.k1 + 1)) / denominator
        return total

    def coverage(self, query_tokens: list[str], position: int) -> float:
        """Share of the query's meaningful words that this record contains.

        Only words that appear somewhere in the corpus are counted. A word like "old" in "how do
        I search old chat" is absent from every record, so including it in the denominator would
        punish the correct answer for the user's phrasing rather than for the match.
        """
        known = {token for token in set(query_tokens) if self.doc_freq.get(token)}
        if not known:
            return 0.0
        document = self.documents[position]
        present = {token for tokens in document.fields.values() for token in tokens}
        return sum(1 for token in known if token in present) / len(known)

    def search(self, query: str, limit: int = 3) -> list[tuple[float, Document]]:
        tokens = index_tokens(query)
        if not tokens:
            return []
        scored = []
        for position, document in enumerate(self.documents):
            score = self.score(tokens, position)
            if score > 0:
                score += (self.phrase_bonus(query, document) + self.name_bonus(query, document)
                          + self.module_affinity(query, document))
                scored.append((score, document))
        scored.sort(key=lambda pair: (-pair[0], pair[1].record_id))
        return scored[:limit]

    def search_confident(self, query: str, limit: int = 3) -> list[tuple[float, Document]]:
        """Search, then drop results that do not clear the floor or cover the question."""
        tokens = index_tokens(query)
        known = {token for token in set(tokens) if self.doc_freq.get(token)}
        words = {token for token in set(tokens) if not IDENTIFIER_RE.search(token)}
        unknown = {token for token in words if not self.doc_freq.get(token)}
        if words and len(unknown) / len(words) >= MAX_UNKNOWN_RATIO:
            return []
        kept = []
        for score, document in self.search(query, limit=limit):
            position = self.documents.index(document)
            if score < CONFIDENCE_FLOOR:
                continue
            strong = (self.phrase_bonus(query, document) >= 8.0
                      or self.module_affinity(query, document) > 0
                      or self.name_bonus(query, document) > 0)
            matched = len(known & {t for ts in document.fields.values() for t in ts})
            if not strong and matched < MIN_MATCHED_TOKENS:
                continue
            if self.coverage(tokens, position) < MIN_QUERY_COVERAGE:
                continue
            kept.append((score, document))
        return kept


def load_documents(dataset_dir: str = DATASET_DIR) -> list[Document]:
    """Read dictionary.jsonl into documents, weighting term and aliases above prose."""
    path = os.path.join(dataset_dir, "dictionary.jsonl")
    if not os.path.exists(path):
        raise SystemExit(
            f"error: {path} is missing. Run: python3 tools/build_datasets.py")

    documents: list[Document] = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            record = json.loads(line)
            # Path placeholders such as {name} are syntax, not content.
            term_text = re.sub(r"\{[^}]*\}", " ", record.get("term", ""))
            fields: dict[str, list[str]] = {
                "term": index_tokens(term_text),
                "definition": index_tokens(record.get("definition", "")),
                "kind": index_tokens(record.get("kind", "")),
            }
            aliases = [t for alias in record.get("aliases", []) for t in index_tokens(alias)]
            if aliases:
                fields["alias"] = aliases
            module = record.get("module", "")
            if module:
                fields["module"] = index_tokens(module)
            documents.append(Document(
                record_id=record.get("id", ""),
                kind=record.get("kind", ""),
                term=record.get("term", ""),
                definition=record.get("definition", ""),
                source=record.get("source", ""),
                module=module,
                fields=fields,
            ))
    return documents


# --------------------------------------------------------------------------- assistant


@dataclass
class Answer:
    text: str
    record_id: Optional[str]
    score: float
    source: str
    kind: str = ""
    term: str = ""
    definition: str = ""
    module: str = ""
    related: tuple[str, ...] = ()

    @property
    def found(self) -> bool:
        return self.record_id is not None


# --------------------------------------------------------------------------- embed rendering

DEFAULT_WIDTH = 48
MAX_WIDTH = 100
TOP_LEFT, TOP_RIGHT = "\u250c", "\u2510"
BOTTOM_LEFT, BOTTOM_RIGHT = "\u2514", "\u2518"
BAR, HORIZONTAL = "\u2502", "\u2500"


def _display_width(text: str) -> int:
    return len(text)


def _shorten(text: str, width: int) -> str:
    if _display_width(text) <= width:
        return text
    if width <= 1:
        return text[:width]
    return text[: width - 1] + "\u2026"


def _wrap(text: str, width: int) -> list[str]:
    """Greedy word wrap. A single word longer than the width is hard-split, never dropped."""
    width = max(1, width)
    if not text:
        return [""]
    words = text.split()
    if not words:
        return [""]
    lines: list[str] = []
    current = ""
    for word in words:
        if not current:
            current = word
        elif len(current) + 1 + len(word) <= width:
            current += " " + word
        else:
            lines.append(current)
            current = word
        while len(current) > width:
            lines.append(current[:width])
            current = current[width:]
    if current:
        lines.append(current)
    return lines


def _border(left: str, right: str, label: str, width: int) -> str:
    text = f" {label} " if label else ""
    room = width - 3
    if _display_width(text) > room:
        text = " " + _shorten(label, max(1, room - 2)) + " "
    fill = max(0, room - _display_width(text))
    return left + HORIZONTAL + text + HORIZONTAL * fill + right


def _fit_path(path: str, width: int) -> str:
    """Shortens a path from the left so it fits one row, keeping the filename.

    A path is a reference, so it has to stay on one row: split across rows it cannot be read or
    copied. The tail is what identifies the file, so the head is what gets dropped.
    """
    width = max(1, width)
    if len(path) <= width:
        return path
    if width == 1:
        return "\u2026"
    return "\u2026" + path[-(width - 1):]


def _rule(name: str, width: int) -> str:
    """A filled rule with the name centred, used as a section divider inside a box."""
    room = max(1, width)
    text = f" {name} " if name else ""
    if _display_width(text) >= room:
        return HORIZONTAL * room
    fill = room - _display_width(text)
    left = fill // 2
    return f"{BAR} {HORIZONTAL * left}{text}{HORIZONTAL * (fill - left)} {BAR}"


def render_embed(title: str, fields: list[tuple[str, str]], footer: str = "",
                 width: int = DEFAULT_WIDTH, path_labels: tuple[str, ...] = ("source",)) -> str:
    """Renders a bordered block, mirroring the mod's ReplyFormat BOX style.

    The game and the Discord webhooks both answer in a titled box, so the CLI uses the same shape:
    one field per row with values in an aligned column, rather than a run-on sentence. Width is
    capped because a wrapped row destroys the alignment the box exists to provide.

    A field whose label is in {@code path_labels} is kept on one row and shortened from the left if
    it cannot fit, because a path split across rows cannot be read. A field with an empty label is
    the body of the block and takes the full width; a field whose label ends with
    {@code !} is a section heading, drawn as a rule with the body beneath it. Prose and lists need
    the whole width — squeezing them into a narrow value column breaks them into a ragged ribbon
    that is harder to read than the sentence it replaced.
    """
    width = width if width > 0 else DEFAULT_WIDTH
    scalar_labels = [label for label, _ in fields if label and not label.endswith("!")]
    label_width = max((_display_width(label) for label in scalar_labels), default=0)
    label_cap = max(8, (width - 5) * 2 // 5)
    if label_width > 0:
        label_width = max(1, min(label_width, min(label_cap, width - 6)))

    lines: list[str] = []
    if title:
        lines.append(_border(TOP_LEFT, TOP_RIGHT, title, width))
    room = max(1, width - (4 if label_width == 0 else 5) - label_width)
    full_room = max(1, width - 4)
    for label, value in fields:
        if label.endswith("!"):
            lines.append(_rule(label[:-1], width - 4))
            for row in _wrap(value, full_room):
                lines.append(f"{BAR} {row.ljust(full_room)} {BAR}")
            continue
        if not label:
            for row in _wrap(value, full_room):
                lines.append(f"{BAR} {row.ljust(full_room)} {BAR}")
            continue
        label_rows = _wrap(label, label_width) if label_width else []
        value_rows = ([_fit_path(value, room)] if label in path_labels
                      else _wrap(value, room))
        for i in range(max(len(label_rows), len(value_rows))):
            left = (label_rows[i] if i < len(label_rows) else "").ljust(label_width)
            right = (value_rows[i] if i < len(value_rows) else "").ljust(room)
            lines.append(f"{BAR} {left} {right} {BAR}" if label_width
                         else f"{BAR} {right} {BAR}")
    lines.append(_border(BOTTOM_LEFT, BOTTOM_RIGHT, footer, width))
    return "\n".join(lines)


def render_answer(document: Document, related: tuple[str, ...] = (), width: int = DEFAULT_WIDTH) -> str:
    """One record as a box: the answer, then what kind of thing it is and where it comes from.

    The box widens when the source path would not fit. A path is a reference the user may want to
    open, and a shortened one sends them to the wrong place, so it is kept whole up to a sane cap.
    """
    fields = [("", document.definition)]
    if related:
        fields.append(("related!", ", ".join(related)))
    fields.append(("kind", document.kind))
    fields.append(("source", document.source))
    width = max(width, min(MAX_WIDTH, len(document.source) + len("source") + 5))
    return render_embed(document.term, fields, footer=document.record_id, width=width)


def plain_answer(document: Document, related: tuple[str, ...] = ()) -> str:
    """The same answer without box drawing, for pipes, logs and terminals without UTF-8."""
    lines = [f"{document.term}: {document.definition}"]
    if related:
        lines.append("related: " + ", ".join(related))
    lines.append(f"{document.kind} · {document.source} · {document.record_id}")
    return "\n".join(lines)


class Assistant:
    """Answers questions from the index, and admits when it cannot."""

    def __init__(self, index: Index, related: Optional[dict[str, list[Document]]] = None,
                 boxed: bool = True, width: int = DEFAULT_WIDTH) -> None:
        self.index = index
        self.related = related or {}
        self.boxed = boxed
        self.width = width

    def answer(self, question: str) -> Answer:
        matches = self.index.search_confident(question, limit=3)
        if not matches or matches[0][0] < CONFIDENCE_FLOOR:
            return Answer(
                text="I do not have that in the dataset. Try `--stats` to see what I cover, or "
                     "rephrase using a module, setting or command name.",
                record_id=None, score=matches[0][0] if matches else 0.0, source="")

        score, document = matches[0]
        siblings: tuple[str, ...] = ()
        if document.kind == "setting" and document.module:
            related = [d for d in self.related.get(document.module, [])
                       if d.record_id != document.record_id][:3]
            siblings = tuple(d.term for d in related)
        text = (render_answer(document, siblings, self.width) if self.boxed
                else plain_answer(document, siblings))
        return Answer(text=text, record_id=document.record_id,
                      score=score, source=document.source, kind=document.kind,
                      term=document.term, definition=document.definition,
                      module=document.module, related=siblings)


def build_related(documents: list[Document]) -> dict[str, list[Document]]:
    """Group settings by module, so an answer can point at its neighbours."""
    groups: dict[str, list[Document]] = {}
    for document in documents:
        if document.kind == "setting" and document.module:
            groups.setdefault(document.module, []).append(document)
    return groups


# --------------------------------------------------------------------------- telemetry


class UsageCounter:
    """Anonymous usage counting.

    The design goal is that this cannot leak what a user typed. It therefore stores:

      * how many times each *public record id* was answered — ids come from this repository and
        contain no user data
      * how many questions were not answered at all, plus which module names those questions
        mentioned, because module names are also public

    It stores no question text, no file paths, no hostname, no username and no clock time finer
    than a day. A question that matches nothing is counted as a number and then forgotten; the
    text is not retained even in memory beyond the call.

    Collection is off until ``opt_in`` is set. ``preview()`` returns exactly what would be
    written, so a user can see it before agreeing to it.
    """

    def __init__(self, path: str = STATE_PATH) -> None:
        self.path = path
        self.opt_in = False
        self.answered: Counter[str] = Counter()
        self.unanswered = 0
        self.unanswered_modules: Counter[str] = Counter()
        self.load()

    # -- persistence

    def load(self) -> None:
        if not os.path.exists(self.path):
            return
        try:
            with open(self.path, encoding="utf-8") as handle:
                data = json.load(handle)
        except (OSError, json.JSONDecodeError):
            return
        self.opt_in = bool(data.get("opt_in"))
        self.answered = Counter(data.get("answered") or {})
        self.unanswered = int(data.get("unanswered") or 0)
        self.unanswered_modules = Counter(data.get("unanswered_modules") or {})

    def save(self) -> None:
        os.makedirs(os.path.dirname(self.path) or ".", exist_ok=True)
        payload = {
            "opt_in": self.opt_in,
            "answered": dict(self.answered),
            "unanswered": self.unanswered,
            "unanswered_modules": dict(self.unanswered_modules),
        }
        with open(self.path, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, indent=2, sort_keys=True)

    # -- recording

    def record(self, question: str, answer: Answer, known_modules: Iterable[str]) -> None:
        """Count one interaction. Does nothing unless the user opted in."""
        if not self.opt_in:
            return
        if answer.found and answer.record_id:
            self.answered[answer.record_id] += 1
            return
        self.unanswered += 1
        # Only the module names mentioned are kept. The question itself is discarded here.
        lowered = (question or "").lower()
        for module in known_modules:
            if module and module in lowered:
                self.unanswered_modules[module] += 1

    # -- reporting

    def preview(self, dataset_records: int = 0) -> dict[str, Any]:
        """Exactly the payload that would be shared. No derived or hidden fields."""
        return {
            "schema": TELEMETRY_SCHEMA,
            "day": time.strftime("%Y-%m-%d"),
            "dataset_records": dataset_records,
            "answered": dict(sorted(self.answered.items())),
            "unanswered": self.unanswered,
            "unanswered_modules": dict(sorted(self.unanswered_modules.items())),
        }

    def summary(self) -> str:
        total = sum(self.answered.values())
        return (f"{total} answered questions across {len(self.answered)} records, "
                f"{self.unanswered} unanswered.")


# --------------------------------------------------------------------------- CLI


def make_assistant(dataset_dir: str = DATASET_DIR, boxed: bool = True,
                   width: int = DEFAULT_WIDTH) -> Assistant:
    documents = load_documents(dataset_dir)
    return Assistant(Index(documents), build_related(documents), boxed=boxed, width=width)


def interactive(assistant: Assistant, usage: UsageCounter, documents: list[Document]) -> int:
    known = {d.module for d in documents if d.module}
    print(f"Log Client assistant — {len(documents)} records. Type a question, or /help.")
    while True:
        try:
            line = input("> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return 0
        if not line:
            continue
        if line in ("/quit", "/exit"):
            return 0
        if line == "/help":
            print("  /stats    what this assistant covers\n"
                  "  /misses   questions it could not answer this session\n"
                  "  /quit     leave")
            continue
        if line == "/stats":
            print("  " + coverage(assistant.index.documents))
            continue
        if line == "/misses":
            print(f"  {usage.unanswered} unanswered." if usage.opt_in
                  else "  Counting is off. Run with --opt-in to enable it.")
            continue
        answer = assistant.answer(line)
        print(answer.text)
        usage.record(line, answer, known)


def coverage(documents: list[Document]) -> str:
    kinds: Counter[str] = Counter(document.kind for document in documents)
    detail = ", ".join(f"{count} {kind}s" for kind, count in sorted(kinds.items()))
    return f"{len(documents)} records: {detail}."


def _coverage_fields(documents: list[Document], usage: "UsageCounter") -> list[tuple[str, str]]:
    kinds: Counter[str] = Counter(document.kind for document in documents)
    modules = {document.module for document in documents if document.module}
    fields: list[tuple[str, str]] = [("records", str(len(documents)))]
    fields.extend((kind, str(count)) for kind, count in sorted(kinds.items()))
    fields.append(("modules", str(len(modules))))
    fields.append(("counting", "on" if usage.opt_in else "off"))
    if usage.opt_in:
        fields.append(("usage", usage.summary()))
    return fields


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        prog="assistant_bot.py",
        description="Offline assistant for Log Client questions.")
    parser.add_argument("--ask", metavar="QUESTION", help="Answer one question and exit")
    parser.add_argument("--stats", action="store_true", help="Show what the assistant covers")
    parser.add_argument("--dataset-dir", default=DATASET_DIR)
    parser.add_argument("--state", default=STATE_PATH, help="Where usage counts are kept")
    parser.add_argument("--opt-in", action="store_true",
                        help="Enable anonymous usage counting (off by default)")
    parser.add_argument("--opt-out", action="store_true", help="Disable and erase usage counts")
    parser.add_argument("--preview-telemetry", action="store_true",
                        help="Print exactly what would be shared, then exit")
    parser.add_argument("--plain", action="store_true",
                        help="No box drawing: for pipes, logs or a terminal without UTF-8")
    parser.add_argument("--width", type=int, default=DEFAULT_WIDTH,
                        help=f"Box width in columns (default {DEFAULT_WIDTH})")
    args = parser.parse_args(argv)

    assistant = make_assistant(args.dataset_dir, boxed=not args.plain, width=args.width)
    documents = assistant.index.documents
    usage = UsageCounter(args.state)

    if args.opt_out:
        usage.opt_in = False
        usage.answered.clear()
        usage.unanswered = 0
        usage.unanswered_modules.clear()
        usage.save()
        print(f"Usage counting is off. Counts erased from {usage.path}.")
        return 0

    if args.opt_in:
        usage.opt_in = True
        usage.save()
        print(f"Usage counting is on. It records only public record ids and counts.\n"
              f"See --preview-telemetry. Stored at {usage.path}.")
        if not (args.ask or args.stats or args.preview_telemetry):
            # Turning counting on is a complete action. Without this it fell through into the
            # REPL and blocked waiting for input, which looks like a hang.
            return 0

    if args.preview_telemetry:
        print(json.dumps(usage.preview(len(documents)), indent=2))
        return 0

    if args.stats:
        if args.plain:
            print(coverage(documents))
            print(f"Usage: {usage.summary()} (counting {'on' if usage.opt_in else 'off'})")
        else:
            print(render_embed("Log Client assistant", _coverage_fields(documents, usage),
                               footer="coverage", width=args.width))
        return 0

    if args.ask:
        answer = assistant.answer(args.ask)
        print(answer.text)
        usage.record(args.ask, answer, {d.module for d in documents if d.module})
        if usage.opt_in:
            usage.save()
        return 0 if answer.found else 1

    try:
        return interactive(assistant, usage, documents)
    finally:
        if usage.opt_in:
            usage.save()


if __name__ == "__main__":
    raise SystemExit(main())
