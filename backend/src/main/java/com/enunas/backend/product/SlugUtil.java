package com.enunas.backend.product;

import java.util.Locale;

/**
 * Generates URL slugs from product names. Must stay byte-for-byte compatible with the
 * frontend's {@code generateSlug} in {@code lib/product.ts}, because the storefront links
 * are built from the slug the backend stores and returns. The base (collision-free) slug
 * for a given name must match what the frontend would produce for that same name.
 */
public final class SlugUtil {

    private SlugUtil() {}

    /** Base slug from a name — same transform as the frontend's generateSlug. Not uniqued. */
    public static String baseSlug(String text) {
        if (text == null) return "";
        String s = text.toLowerCase(Locale.ROOT);
        s = s.replace("ä", "ae")
             .replace("ö", "oe")
             .replace("ü", "ue")
             .replace("ß", "ss");
        s = s.replaceAll("[^\\w\\s-]", ""); // \w is ASCII [A-Za-z0-9_], matching JS
        s = s.replaceAll("\\s+", "-");
        s = s.replaceAll("--+", "-");
        return s.strip();
    }
}
