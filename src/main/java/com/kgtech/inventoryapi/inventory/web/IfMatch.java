package com.kgtech.inventoryapi.inventory.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.kgtech.inventoryapi.inventory.DetailsPrecondition;

/**
 * The If-Match header (RFC 9110 §13.1.1) as a {@link DetailsPrecondition} (DESIGN-V2 §8 "Edit"). Absent or "*" is
 * Any. Otherwise a comma-separated list of entity-tags: strong tags whose content is the details version count;
 * weak tags (W/"…") and non-numeric tags are well-formed but can never match strongly. Anything else is malformed:
 * empty (400).
 */
final class IfMatch {

    private IfMatch() {
    }

    static Optional<DetailsPrecondition> parse(List<String> headerValues) {
        if (headerValues.isEmpty()) {
            return Optional.of(new DetailsPrecondition.Any());
        }
        String header = String.join(",", headerValues).strip();
        if (header.equals("*")) {
            return Optional.of(new DetailsPrecondition.Any());
        }
        List<Long> versions = new ArrayList<>();
        int i = 0;
        boolean expectTag = true;
        while (i < header.length()) {
            char c = header.charAt(i);
            if (c == ' ' || c == '\t') {
                i++;
            } else if (expectTag) {
                boolean weak = header.startsWith("W/", i);
                int open = weak ? i + 2 : i;
                if (open >= header.length() || header.charAt(open) != '"') {
                    return Optional.empty();
                }
                int close = header.indexOf('"', open + 1);
                if (close < 0) {
                    return Optional.empty();
                }
                if (!weak) {
                    version(header.substring(open + 1, close)).ifPresent(versions::add);
                }
                i = close + 1;
                expectTag = false;
            } else if (c == ',') {
                i++;
                expectTag = true;
            } else {
                return Optional.empty();
            }
        }
        if (expectTag) {
            return Optional.empty(); // "" or a trailing comma
        }
        return Optional.of(new DetailsPrecondition.Versions(versions));
    }

    /** A non-negative decimal of at most 18 digits; anything else is a tag this server never issued. */
    private static Optional<Long> version(String tag) {
        if (tag.isEmpty() || tag.length() > 18 || !tag.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(tag));
    }

    static String etag(long version) {
        return "\"" + version + "\"";
    }
}
