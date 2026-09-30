package com.kgtech.inventoryapi.inventory;

import java.util.List;

/** What a PUT of details requires of the current details version: nothing, or one of these. */
public sealed interface DetailsPrecondition {

    /** No condition: last write wins. */
    record Any() implements DetailsPrecondition {
    }

    /** PUT details, If-Match: * (RFC 9110 §13.1.1): a current representation must exist, so the SKU must exist. */
    record Exists() implements DetailsPrecondition {
    }

    /** PUT details, If-None-Match: * (RFC 9110 §13.1.2): the SKU must not exist yet. */
    record Absent() implements DetailsPrecondition {
    }

    /** The replacement applies only when the current version is listed; an empty list never matches (412). */
    record Versions(List<Long> versions) implements DetailsPrecondition {

        public Versions {
            versions = List.copyOf(versions);
        }
    }
}
