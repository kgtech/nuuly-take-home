package com.kgtech.inventoryapi.inventory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A SKU's details: what PUT accepts and what GET returns inside a {@link SkuItem} (DESIGN-V2 §8). The record enforces
 * the field rules, so a value the contract forbids cannot be built, whichever path tries. Absent optional values are
 * omitted from JSON, never written as null.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
public record SkuDetails(
        @Schema(description = "1 to 120 characters, not blank", minLength = 1, maxLength = SkuDetails.MAX_NAME,
                requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(description = "Up to 2000 characters; default \"\"", maxLength = SkuDetails.MAX_DESCRIPTION)
        String description,
        @Schema(implementation = SkuCost.class) Optional<SkuCost> cost,
        @ArraySchema(maxItems = SkuDetails.MAX_IMAGES, arraySchema = @Schema(
                description = "Up to 10 absolute http or https URLs in ASCII (percent-encoded); default []"),
                schema = @Schema(type = "string", format = "uri", maxLength = SkuDetails.MAX_URL))
        List<String> images) {

    public static final int MAX_NAME = 120;
    public static final int MAX_DESCRIPTION = 2000;
    public static final int MAX_IMAGES = 10;
    public static final int MAX_URL = 2048;

    public SkuDetails {
        if (name == null || name.isBlank() || name.length() > MAX_NAME) {
            throw new IllegalArgumentException("name must be 1 to " + MAX_NAME + " characters and not blank");
        }
        requirePrintable(name, false);
        description = description == null ? "" : description;
        if (description.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("description must be at most " + MAX_DESCRIPTION + " characters");
        }
        requirePrintable(description, true);
        cost = cost == null ? Optional.empty() : cost;
        images = images == null ? List.of() : List.copyOf(images);
        if (images.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("at most " + MAX_IMAGES + " images");
        }
        for (String image : images) {
            requireAbsoluteHttpUrl(image);
        }
    }

    /**
     * No control characters (a newline and a tab are allowed in a description) and no unpaired surrogate: Postgres
     * text cannot hold NUL and a lone surrogate cannot be encoded, and either would answer 500 instead of 400
     * (review R-02). Lengths are UTF-16 units, the same as the JSON string's length in JavaScript.
     */
    private static void requirePrintable(String value, boolean multiline) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = c >= 0x20 && c != 0x7F || multiline && (c == '\n' || c == '\t');
            if (!allowed) {
                throw new IllegalArgumentException("control character in text");
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException("unpaired surrogate in text");
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("unpaired surrogate in text");
            }
        }
    }

    /**
     * An absolute http or https URL of at most 2048 ASCII characters (percent-encoded; review R-05), so its byte
     * length is its length and the 32 KB body cap always fits a contract-valid body. URI rejects spaces and controls.
     */
    private static void requireAbsoluteHttpUrl(String image) {
        if (image.length() > MAX_URL) {
            throw new IllegalArgumentException("image URL longer than " + MAX_URL);
        }
        URI uri;
        try {
            uri = new URI(image);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("image is not a URL", e);
        }
        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || !("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
            throw new IllegalArgumentException("image must be an absolute http or https URL");
        }
        if (!uri.toASCIIString().equals(image)) {
            throw new IllegalArgumentException("image URL must be ASCII (percent-encoded)");
        }
    }
}
