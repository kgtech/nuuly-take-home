package com.kgtech.inventoryapi.inventory.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.kgtech.inventoryapi.inventory.DetailsPrecondition;

/**
 * The If-Match header (RFC 9110 §13.1.1) as a {@link DetailsPrecondition} for PUT .../details. Absent is Any. Otherwise a comma-separated list of entity-tags: strong tags whose content is the details version count;
 * weak tags (W/"…") and non-numeric tags are well-formed but can never match strongly. Anything else is malformed:
 * empty (400).
 */
final class IfMatch {

    private IfMatch() {
    }

    private static Optional<DetailsPrecondition> parse(List<String> headerValues) {
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

    /**
     * Exactly the digits this server issues (no leading zeros, at most 18 digits): strong comparison is byte-wise
     * (RFC 9110 §8.8.3.2), so "01" is a tag this server never issued and never matches (review R-04).
     */
    private static Optional<Long> version(String tag) {
        if (tag.isEmpty() || tag.length() > 18 || !tag.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            return Optional.empty();
        }
        long value = Long.parseLong(tag);
        return Long.toString(value).equals(tag) ? Optional.of(value) : Optional.empty();
    }

    /**
     * The conditions of PUT .../details (OD-11, RFC 9110 §13.1): here If-Match "*" means a current representation
     * exists (Exists), not "unconditional" as on the old PUT, and If-None-Match accepts only "*" (Absent). Both
     * present fail on every SKU, so they give an empty version list, which never matches (412). Malformed values are
     * empty (400), checked before that.
     */
    static Optional<DetailsPrecondition> parsePut(List<String> ifMatch, List<String> ifNoneMatch) {
        boolean noneMatchStar = !ifNoneMatch.isEmpty();
        if (noneMatchStar && !String.join(",", ifNoneMatch).strip().equals("*")) {
            return Optional.empty();
        }
        Optional<DetailsPrecondition> match = ifMatch.isEmpty() ? Optional.of(new DetailsPrecondition.Any())
                : String.join(",", ifMatch).strip().equals("*") ? Optional.of(new DetailsPrecondition.Exists())
                : parse(ifMatch);
        if (!ifMatch.isEmpty() && match.isEmpty()) {
            return Optional.empty(); // a malformed If-Match is 400 whatever else was sent
        }
        if (noneMatchStar && match.isPresent() && !ifMatch.isEmpty()) {
            return Optional.of(new DetailsPrecondition.Versions(List.of()));
        }
        return noneMatchStar ? Optional.of(new DetailsPrecondition.Absent()) : match;
    }

    static String etag(long version) {
        return "\"" + version + "\"";
    }
}
