package com.logvex.addon.schematicdropper;

import com.logvex.addon.schematicdropper.mc.FileDialogs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The native file dialog takes a NULL-terminated filter array. A missing terminator is undefined
 * behaviour that crashed the client when any import button was pressed, so the contract is pinned
 * here.
 */
class FileDialogsTest {

    @Test
    @DisplayName("filters are NULL-terminated for the native dialog")
    void filtersAreTerminated() {
        String[] filters = FileDialogs.terminatedFilters("*.txt", "*.md");
        assertArrayEquals(new String[]{"*.txt", "*.md", null}, filters);
    }

    @Test
    @DisplayName("the terminator is present even with no filters")
    void noFiltersStillTerminates() {
        assertEquals(1, FileDialogs.terminatedFilters().length);
        assertNull(FileDialogs.terminatedFilters()[0]);
    }

    @Test
    @DisplayName("the working directory is an absolute path")
    void workingDirectoryIsAbsolute() {
        String dir = FileDialogs.workingDirectory();
        assertEquals(true, new java.io.File(dir).isAbsolute());
    }
}
