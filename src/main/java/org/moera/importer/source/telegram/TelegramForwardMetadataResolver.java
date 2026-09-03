package org.moera.importer.source.telegram;

public interface TelegramForwardMetadataResolver {

    TelegramForwardMetadataResolver EMPTY = new TelegramForwardMetadataResolver() {

        @Override
        public Source user(long userId) {
            return null;
        }

        @Override
        public Source chat(long chatId) {
            return null;
        }

    };

    Source user(long userId);

    Source chat(long chatId);

    default String messageLink(long chatId, long messageId) {
        Source source = chat(chatId);
        long publicMessageId = messageId >> 20;
        if (source == null || source.username() == null || source.username().isBlank() || publicMessageId <= 0) {
            return null;
        }
        return "https://t.me/" + source.username() + "/" + publicMessageId;
    }

    record Source(String name, String username) {
    }

}
