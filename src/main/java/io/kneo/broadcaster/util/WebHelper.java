package io.kneo.broadcaster.util;

import io.kneo.core.localization.LanguageCode;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class WebHelper {
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\s]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s");

    public static String generateSlug(String title, String artist) {
        if (title == null) title = "";
        if (artist == null) artist = "";
        String input = title + " " + artist;
        String noWhitespace = WHITESPACE.matcher(input).replaceAll("-");
        String normalized = Normalizer.normalize(noWhitespace, Normalizer.Form.NFC);
        String slug = UNSAFE_CHARS.matcher(normalized).replaceAll("");
        return slug.toLowerCase();
    }

    public static String generateSlug(String input) {
        String fileName;
        String extension = "";

        int lastDotIndex = input.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < input.length() - 1) {
            fileName = input.substring(0, lastDotIndex);
            extension = input.substring(lastDotIndex);
        } else {
            fileName = input;
        }

        String noWhitespace = WHITESPACE.matcher(fileName).replaceAll("-");
        String normalized = Normalizer.normalize(noWhitespace, Normalizer.Form.NFC);
        String slugPart = UNSAFE_CHARS.matcher(normalized).replaceAll("");
        String slug = slugPart.toLowerCase();

        return slug + extension;
    }

    public static String generateSlugPath(String... segments) {
        if (segments == null || segments.length == 0) {
            return "";
        }

        return Arrays.stream(segments)
                .map(WebHelper::generateSlug)
                .filter(slug -> !slug.isEmpty())
                .collect(Collectors.joining("/"));
    }

    public static String generateSlug(EnumMap<LanguageCode, String> localizedName) {
        if (localizedName == null || localizedName.isEmpty()) {
            return "";
        }

        String name = localizedName.get(LanguageCode.en);
        if (name == null || name.trim().isEmpty()) {
            name = localizedName.values().stream()
                    .filter(value -> value != null && !value.trim().isEmpty())
                    .findFirst()
                    .orElse("");
        }

        return generateSlug(name);
    }
}