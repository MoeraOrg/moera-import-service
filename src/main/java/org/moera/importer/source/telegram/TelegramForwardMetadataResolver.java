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

    record Source(String name, String username) {
    }

}
