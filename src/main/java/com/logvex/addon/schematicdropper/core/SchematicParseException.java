package com.logvex.addon.schematicdropper.core;

/** Raised when a schematic file cannot be understood. Carries a user-facing reason. */
public class SchematicParseException extends Exception {
    public SchematicParseException(String message) {
        super(message);
    }

    public SchematicParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
