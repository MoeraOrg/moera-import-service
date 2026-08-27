package org.moera.importer.source.telegram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

import org.moera.importer.source.MediaSnapshot;
import org.moera.importer.source.SourcePermanentException;
import org.moera.importer.source.SourceTemporaryException;

public class TelegramMediaDownloader {

    private final TelegramClient client;

    public TelegramMediaDownloader(TelegramClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public void download(MediaSnapshot media, Path destination) {
        String remoteId = media.sourceData().path("remoteId").textValue();
        String fileType = media.sourceData().path("fileType").textValue();
        if (remoteId == null || fileType == null) {
            throw new SourcePermanentException("Telegram media lacks remote file metadata");
        }
        TelegramClient.RemoteFile remoteFile = client.reacquire(remoteId, fileType);
        TelegramClient.DownloadedFile downloaded = client.download(remoteFile.fileId());
        try {
            Files.copy(downloaded.path(), destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new SourceTemporaryException("Cannot copy downloaded Telegram media", e);
        } finally {
            client.deleteCachedFile(downloaded.fileId());
        }
    }

}
