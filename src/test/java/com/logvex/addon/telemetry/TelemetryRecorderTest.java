package com.logvex.addon.telemetry;

import com.logvex.addon.telemetry.core.TelemetryRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The telemetry ring: it must keep the newest samples, report honest statistics, and never turn a
 * missing reading into a zero. A graph built on this data is only worth having if a gap stays a gap.
 */
class TelemetryRecorderTest {

    @Test
    @DisplayName("a series keeps only its most recent samples once it is full")
    void ringKeepsNewestSamples() {
        TelemetryRecorder recorder = new TelemetryRecorder();
        recorder.setCapacity(3);
        for (int i = 1; i <= 5; i++) recorder.record("tps", i * 1000L, i);

        TelemetryRecorder.Series series = recorder.series("tps");
        assertNotNull(series);
        assertEquals(3, series.size());
        assertEquals(List.of(3.0, 4.0, 5.0), series.values(), "the three newest samples survive");
        assertEquals(List.of(3000L, 4000L, 5000L), series.timestamps());
    }

    @Test
    @DisplayName("statistics describe the retained window, not the whole history")
    void statisticsDescribeRetainedWindow() {
        TelemetryRecorder recorder = new TelemetryRecorder();
        recorder.setCapacity(3);
        for (double v : new double[]{100, 1, 2, 3}) recorder.record("fps", 0L, v);

        TelemetryRecorder.Series series = recorder.series("fps");
        assertEquals(1.0, series.min(), 1e-9);
        assertEquals(3.0, series.max(), 1e-9);
        assertEquals(2.0, series.average(), 1e-9);
        assertEquals(3.0, series.latest(), 1e-9);
    }

    @Test
    @DisplayName("a missing reading is not recorded, so a gap stays a gap")
    void invalidReadingsAreNotSamples() {
        TelemetryRecorder recorder = new TelemetryRecorder();
        recorder.record("tps", 1000L, Double.NaN);
        recorder.record("tps", 2000L, Double.POSITIVE_INFINITY);
        recorder.record("tps", 3000L, 20.0);

        TelemetryRecorder.Series series = recorder.series("tps");
        assertEquals(1, series.size(), "only the real reading was kept");
        assertEquals(20.0, series.latest(), 1e-9);
    }

    @Test
    @DisplayName("a metric with no samples has no series at all")
    void emptyMetricIsAbsent() {
        TelemetryRecorder recorder = new TelemetryRecorder();
        assertNull(recorder.series("never-recorded"));
        assertTrue(recorder.metrics().isEmpty());
    }

    @Test
    @DisplayName("metrics are listed in the order they were first recorded")
    void metricsAreOrdered() {
        TelemetryRecorder recorder = new TelemetryRecorder();
        recorder.record("fps", 1L, 60);
        recorder.record("ping", 1L, 30);
        recorder.record("fps", 2L, 61);

        assertEquals(List.of("fps", "ping"), recorder.metrics());
    }

    @Test
    @DisplayName("downsampling reduces the point count and preserves the value range")
    void downsamplingPreservesShape() {
        TelemetryRecorder recorder = new TelemetryRecorder();
        recorder.setCapacity(100);
        for (int i = 0; i < 100; i++) recorder.record("fps", i * 1000L, 60 + (i % 2));

        TelemetryRecorder.Series down = TelemetryRecorder.downsample(recorder.series("fps"), 10);
        assertEquals(10, down.size());
        // Every bucket averages one 60 and one 61, so the mean is 60.5 throughout.
        for (double value : down.values()) assertEquals(60.5, value, 1e-9);
        assertTrue(down.timestamps().get(0) < down.timestamps().get(9), "time still increases");
    }

    @Test
    @DisplayName("downsampling a short series returns it unchanged rather than padding it")
    void shortSeriesIsNotPadded() {
        TelemetryRecorder recorder = new TelemetryRecorder();
        recorder.record("fps", 1L, 60);
        recorder.record("fps", 2L, 61);

        TelemetryRecorder.Series down = TelemetryRecorder.downsample(recorder.series("fps"), 10);
        assertEquals(2, down.size(), "no invented points");
    }

    @Test
    @DisplayName("resizing keeps the newest samples and discards the rest")
    void resizeKeepsNewest() {
        TelemetryRecorder recorder = new TelemetryRecorder();
        recorder.setCapacity(10);
        for (int i = 1; i <= 10; i++) recorder.record("fps", i, i);

        recorder.setCapacity(3);
        assertEquals(List.of(8.0, 9.0, 10.0), recorder.series("fps").values());

        recorder.setCapacity(20);
        assertEquals(3, recorder.series("fps").size());
    }

    @Test
    @DisplayName("clearing drops every metric")
    void clearDropsEverything() {
        TelemetryRecorder recorder = new TelemetryRecorder();
        recorder.record("fps", 1L, 60);
        recorder.clear();

        assertTrue(recorder.metrics().isEmpty());
        assertNull(recorder.series("fps"));
    }

    @Test
    @DisplayName("a downsample of nothing yields an empty series, not a zero at the origin")
    void downsamplingNothingYieldsNothing() {
        TelemetryRecorder.Series empty = TelemetryRecorder.downsample(null, 10);
        assertEquals(0, empty.size());
        assertEquals(Double.NaN, empty.latest());
        assertEquals(Double.NaN, empty.average());
    }
}
