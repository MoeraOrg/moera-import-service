package org.moera.importer.source.telegram;

import java.util.Objects;
import java.util.regex.Pattern;

import org.moera.importer.source.ForwardAttribution;

public final class TelegramForwardAttribution {

    private static final Pattern MESSAGE_ID = Pattern.compile("/[0-9]+$");

    private TelegramForwardAttribution() {
    }

    public static boolean sameSource(ForwardAttribution previous, ForwardAttribution next) {
        if (previous == null || next == null || previous.uri() == null || next.uri() == null) {
            return Objects.equals(previous, next);
        }
        return sourceUri(previous).equals(sourceUri(next));
    }

    private static String sourceUri(ForwardAttribution attribution) {
        return MESSAGE_ID.matcher(attribution.uri()).replaceFirst("");
    }

}
