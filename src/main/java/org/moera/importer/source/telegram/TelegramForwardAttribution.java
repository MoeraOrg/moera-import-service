package org.moera.importer.source.telegram;

import java.util.Objects;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
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

    public static ChannelOrigin channelOrigin(JsonNode sourceData) {
        String chatId = sourceData.path("forwardOriginChatId").asText(null);
        String messageId = sourceData.path("forwardOriginMessageId").asText(null);
        return chatId != null && messageId != null ? new ChannelOrigin(chatId, messageId) : null;
    }

    private static String sourceUri(ForwardAttribution attribution) {
        return MESSAGE_ID.matcher(attribution.uri()).replaceFirst("");
    }

    public record ChannelOrigin(String chatId, String messageId) {
    }

}
