package com.logvex.addon.schematicdropper.core;

/** Order in which materials are processed. The chosen order stays stable for the whole run. */
public enum OrderingMode {
    SCHEMATIC_ORDER,
    ALPHABETICAL,
    QUANTITY_DESCENDING,
    QUANTITY_ASCENDING;

    @Override
    public String toString() {
        return switch (this) {
            case SCHEMATIC_ORDER -> "Schematic Order";
            case ALPHABETICAL -> "Alphabetical";
            case QUANTITY_DESCENDING -> "Quantity (High to Low)";
            case QUANTITY_ASCENDING -> "Quantity (Low to High)";
        };
    }
}
