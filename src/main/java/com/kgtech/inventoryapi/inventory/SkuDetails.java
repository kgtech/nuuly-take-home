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
                description = "Up to 10 absolute http or https URLs; default []"),
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
        description = description == null ? "" : description;
        if (description.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("description must be at most " + MAX_DESCRIPTION + " characters");
        }
        cost = cost == null ? Optional.empty() : cost;
        images = images == null ? List.of() : List.copyOf(images);
        if (images.size() > MAX_IMAGES) {
            throw new IllegalArgumentException("at most " + MAX_IMAGES + " images");
        }
        for (String image : images) {
            requireAbsoluteHttpUrl(image);
        }
    }

    /** An absolute http or https URL of at most 2048 characters (no spaces or control characters: URI rejects them). */
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
    }
}
