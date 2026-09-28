package com.kgtech.inventoryapi.inventory;

import java.util.List;

/** What a PUT of details requires of the current details version (DESIGN-V2 §8 "Edit"): nothing, or one of these. */
public sealed interface DetailsPrecondition {

    /** No If-Match, or "*": last write wins. */
    record Any() implements DetailsPrecondition {
    }

    /** The replacement applies only when the current version is listed; an empty list never matches (412). */
    record Versions(List<Long> versions) implements DetailsPrecondition {

        public Versions {
            versions = List.copyOf(versions);
        }
    }
}
