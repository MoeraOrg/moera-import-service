package org.moera.importer.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.moera.importer.config.WorkerProperties;
import org.moera.importer.model.Media;
import org.moera.importer.model.MediaSkipReason;
import org.moera.importer.moera.MediaUpload;
import org.moera.importer.moera.MoeraConfigurationException;
import org.moera.importer.moera.MoeraMediaTooLargeException;
import org.moera.importer.moera.MoeraOutcomeUnknownException;
import org.moera.importer.moera.MoeraPermanentException;
import org.moera.importer.moera.MoeraService;
import org.moera.importer.moera.MoeraTemporaryException;
import org.moera.importer.moera.UploadedMedia;
import org.moera.importer.persistence.PublicationMediaClaim;
import org.moera.importer.persistence.PublicationMediaContext;
import org.moera.importer.persistence.PublicationMediaRepository;
import org.moera.importer.source.MediaSnapshot;
import org.moera.importer.source.SourceAdapter;
import org.moera.importer.source.SourceAdapterRegistry;
import org.moera.importer.source.SourcePermanentException;
import org.moera.importer.source.SourceTemporaryException;
import org.moera.importer.staging.StagingFileManager;
import org.springframework.stereotype.Component;

@Component
public class PublicationMediaProcessor {

    @Inject
    private Clock clock;

    @Inject
    private MoeraService moeraService;

    @Inject
    private PublicationMediaRepository publicationMediaRepository;

    @Inject
    private RetryPolicy retryPolicy;

    @Inject
    private SourceAdapterRegistry sourceAdapterRegistry;

    @Inject
    private StagingFileManager stagingFileManager;

    @Inject
    private WorkerProperties workerProperties;

    public void process(PublicationMediaClaim claim) {
        PublicationMediaContext context = publicationMediaRepository.loadContext(
            claim.publicationId(), claim.mediaId()
        );
        if (compressionPending(context)) {
            processCompression(claim, context);
            return;
        }
        processUpload(claim, context);
    }

    private void processUpload(PublicationMediaClaim claim, PublicationMediaContext context) {
        Path stagingPath = null;
        boolean uploadStarted = false;
        try {
            long mediaMaxSize = moeraService.capabilities(context.destination()).mediaMaxSize();
            if (context.media().size() != null && context.media().size() > mediaMaxSize) {
                skipTooLarge(claim, context);
                delete(stagingPath(context));
                return;
            }

            stagingPath = stagingPath(context);
            if (Files.notExists(stagingPath)) {
                SourceAdapter sourceAdapter = sourceAdapterRegistry.get(context.channel().sourceType());
                sourceAdapter.downloadMedia(context.channel(), snapshot(context.media()), stagingPath);
            }
            if (Files.size(stagingPath) > mediaMaxSize) {
                skipTooLarge(claim, context);
                delete(stagingPath);
                return;
            }

            uploadStarted = true;
            MediaUpload upload = moeraService.uploadMedia(context.destination(), stagingPath, context.media());
            completeUpload(claim, context, upload);
            delete(stagingPath);
        } catch (MoeraMediaTooLargeException e) {
            skipTooLarge(claim, context);
            delete(stagingPath);
        } catch (SourcePermanentException e) {
            publicationMediaRepository.skipped(
                claim.publicationId(),
                claim.mediaId(),
                claim.processingToken(),
                context.media().sourceHash(),
                MediaSkipReason.SOURCE_UNAVAILABLE
            );
            delete(stagingPath);
        } catch (MoeraPermanentException | MoeraConfigurationException e) {
            publicationMediaRepository.fail(
                claim.publicationId(), claim.mediaId(), claim.processingToken(), e.getMessage()
            );
            delete(stagingPath);
        } catch (SourceTemporaryException e) {
            retry(claim, e.retryAfter(), e.getMessage());
            if (!uploadStarted) {
                delete(stagingPath);
            }
        } catch (MoeraTemporaryException | MoeraOutcomeUnknownException e) {
            retry(claim, null, e.getMessage());
            if (!uploadStarted) {
                delete(stagingPath);
            }
        } catch (IOException | UncheckedIOException e) {
            retry(claim, null, "Cannot access staging file");
            if (!uploadStarted) {
                delete(stagingPath);
            }
        }
    }

    private void processCompression(PublicationMediaClaim claim, PublicationMediaContext context) {
        try {
            MediaUpload upload = moeraService.checkMediaCompression(
                context.destination(), context.destinationMediaId()
            );
            completeUpload(claim, context, upload);
        } catch (MoeraPermanentException | MoeraConfigurationException e) {
            publicationMediaRepository.fail(
                claim.publicationId(), claim.mediaId(), claim.processingToken(), e.getMessage()
            );
        } catch (MoeraTemporaryException | MoeraOutcomeUnknownException e) {
            retry(claim, null, e.getMessage());
        }
    }

    private void completeUpload(
        PublicationMediaClaim claim,
        PublicationMediaContext context,
        MediaUpload upload
    ) {
        if (upload instanceof MediaUpload.Ready ready) {
            UploadedMedia media = ready.media();
            publicationMediaRepository.uploaded(
                claim.publicationId(),
                claim.mediaId(),
                claim.processingToken(),
                context.media().sourceHash(),
                media.id(),
                destinationData(media)
            );
        } else if (upload instanceof MediaUpload.AwaitingCompression awaiting) {
            UploadedMedia media = awaiting.original();
            publicationMediaRepository.awaitingCompression(
                claim.publicationId(),
                claim.mediaId(),
                claim.processingToken(),
                context.media().sourceHash(),
                media.id(),
                destinationData(media),
                clock.instant().plus(workerProperties.getMediaCompressionPollInterval())
            );
        }
    }

    private void skipTooLarge(PublicationMediaClaim claim, PublicationMediaContext context) {
        publicationMediaRepository.skipped(
            claim.publicationId(),
            claim.mediaId(),
            claim.processingToken(),
            context.media().sourceHash(),
            MediaSkipReason.TOO_LARGE
        );
    }

    private void retry(PublicationMediaClaim claim, Duration retryAfter, String error) {
        Instant nextAttemptAt = clock.instant().plus(retryPolicy.delay(claim.attempts(), retryAfter));
        publicationMediaRepository.retry(
            claim.publicationId(), claim.mediaId(), claim.processingToken(), nextAttemptAt, error
        );
    }

    private boolean compressionPending(PublicationMediaContext context) {
        return context.destinationMediaId() != null
            && context.destinationData().path("compressionPending").asBoolean(false);
    }

    private Path stagingPath(PublicationMediaContext context) {
        return stagingFileManager.path(
            context.publicationId(), context.media().id(), context.media().sourceHash()
        );
    }

    private MediaSnapshot snapshot(Media media) {
        return new MediaSnapshot(
            media.id(),
            media.messageId(),
            media.sourceId(),
            media.position(),
            media.mimeType(),
            media.fileName(),
            media.size(),
            media.sourceHash(),
            media.sourceData()
        );
    }

    private JsonNode destinationData(UploadedMedia media) {
        return JsonNodeFactory.instance.objectNode().put("hash", media.hash());
    }

    private void delete(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Orphan staging files are removed by periodic cleanup.
        }
    }

}
