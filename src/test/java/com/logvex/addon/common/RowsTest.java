package com.logvex.addon.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowsTest {

    private static List<Integer> numbers(int count) {
        return IntStream.rangeClosed(1, count).boxed().toList();
    }

    @Test
    @DisplayName("a list that fits is shown whole with nothing remaining")
    void fits() {
        Rows.Capped<Integer> capped = Rows.cap(numbers(3), 20);
        assertEquals(List.of(1, 2, 3), capped.shown());
        assertEquals(0, capped.remaining());
        assertFalse(capped.truncated());
        assertEquals("", Rows.remainderNote(capped));
    }

    @Test
    @DisplayName("exactly at the limit is not truncated")
    void exactlyAtLimit() {
        Rows.Capped<Integer> capped = Rows.cap(numbers(20), 20);
        assertEquals(20, capped.shown().size());
        assertEquals(0, capped.remaining());
        assertFalse(capped.truncated());
    }

    @Test
    @DisplayName("one over the limit reports exactly one remaining")
    void oneOver() {
        // The off-by-one this helper exists to prevent: a hand-written loop that advances its
        // counter on the row that breaks out reports "0 more" for a list of 21.
        Rows.Capped<Integer> capped = Rows.cap(numbers(21), 20);
        assertEquals(20, capped.shown().size());
        assertEquals(1, capped.remaining());
        assertTrue(capped.truncated());
        assertEquals("1 more not shown", Rows.remainderNote(capped));
    }

    @Test
    @DisplayName("the remainder counts every hidden row")
    void manyOver() {
        Rows.Capped<Integer> capped = Rows.cap(numbers(100), 20);
        assertEquals(80, capped.remaining());
        assertEquals("80 more not shown", Rows.remainderNote(capped));
    }

    @Test
    @DisplayName("the shown rows keep their order and are the first ones")
    void order() {
        Rows.Capped<Integer> capped = Rows.cap(numbers(50), 3);
        assertEquals(List.of(1, 2, 3), capped.shown());
    }

    @Test
    @DisplayName("a zero or negative limit shows nothing and counts everything as remaining")
    void nonPositiveLimit() {
        Rows.Capped<Integer> none = Rows.cap(numbers(4), 0);
        assertTrue(none.shown().isEmpty());
        assertEquals(4, none.remaining());

        Rows.Capped<Integer> negative = Rows.cap(numbers(4), -5);
        assertTrue(negative.shown().isEmpty());
        assertEquals(4, negative.remaining());
    }

    @Test
    @DisplayName("an empty or null list is empty, not truncated")
    void empty() {
        Rows.Capped<Integer> empty = Rows.cap(Collections.emptyList(), 10);
        assertTrue(empty.shown().isEmpty());
        assertEquals(0, empty.remaining());
        assertFalse(empty.truncated());

        Rows.Capped<Integer> nullList = Rows.cap(null, 10);
        assertTrue(nullList.shown().isEmpty());
        assertEquals(0, nullList.remaining());
    }

    @Test
    @DisplayName("the shown rows are a snapshot, so later list changes cannot leak through")
    void immutable() {
        List<Integer> source = numbers(5);
        Rows.Capped<Integer> capped = Rows.cap(source, 2);
        assertEquals(List.of(1, 2), capped.shown());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
            () -> capped.shown().add(99));
    }
}
