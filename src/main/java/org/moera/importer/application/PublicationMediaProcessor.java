package org.moera.importer.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import jakarta.inject.Inject;

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
import org.moera.importer.observability.ImportMetrics;
import org.moera.importer.persistence.PublicationMediaClaim;
import org.moera.importer.persistence.PublicationMediaContext;
import org.moera.importer.persistence.PublicationMediaRepository;
import org.moera.importer.source.MediaSnapshot;
import org.moera.importer.source.SourceAdapter;
import org.moera.importer.source.SourceAdapterRegistry;
import org.moera.importer.source.SourcePermanentException;
import org.moera.importer.source.SourceTemporaryException;
import org.moera.importer.staging.StagingFileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PublicationMediaProcessor {

    private static final Logger log = LoggerFactory.getLogger(PublicationMediaProcessor.class);

    @Inject
    private Clock clock;

    @Inject
    private MoeraService moeraService;

    @Inject
    private ImportMetrics importMetrics;

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
        started(claim);
        try {
            stage(claim, "context_load_started");
            PublicationMediaContext context = publicationMediaRepository.loadContext(
                claim.publicationId(), claim.mediaId()
            );
            stage(claim, "context_loaded");
            if (compressionPending(context)) {
                processCompression(claim, context);
                return;
            }
            processUpload(claim, context);
        } catch (RuntimeException e) {
            unexpectedFailure(claim, e);
            throw e;
        }
    }

    private void processUpload(PublicationMediaClaim claim, PublicationMediaContext context) {
        Path stagingPath = null;
        boolean uploadStarted = false;
        try {
            stage(claim, "capabilities_load_started");
            long mediaMaxSize = moeraService.capabilities(context.destination()).mediaMaxSize();
            stage(claim, "capabilities_loaded");
            if (context.media().size() != null && context.media().size() > mediaMaxSize) {
                skipTooLarge(claim, context);
                delete(stagingPath(context));
                return;
            }

            stagingPath = stagingPath(context);
            if (Files.notExists(stagingPath)) {
                SourceAdapter sourceAdapter = sourceAdapterRegistry.get(context.channel().sourceType());
                stage(claim, "download_started");
                sourceAdapter.downloadMedia(context.channel(), snapshot(context.media()), stagingPath);
                stage(claim, "download_finished");
            }
            long stagingSize = Files.size(stagingPath);
            if (stagingSize > mediaMaxSize) {
                skipTooLarge(claim, context);
                delete(stagingPath);
                return;
            }

            uploadStarted = true;
            importMetrics.mediaUploadBytes(stagingSize);
            stage(claim, "upload_started");
            MediaUpload upload = moeraService.uploadMedia(context.destination(), stagingPath, context.media());
            stage(claim, "upload_finished");
            completeUpload(claim, context, upload);
            delete(stagingPath);
        } catch (MoeraMediaTooLargeException e) {
            skipTooLarge(claim, context);
            delete(stagingPath);
        } catch (SourcePermanentException e) {
            boolean completed = publicationMediaRepository.skipped(
                claim.publicationId(),
                claim.mediaId(),
                claim.processingToken(),
                context.media().sourceHash(),
                MediaSkipReason.SOURCE_UNAVAILABLE
            );
            skipped(claim, MediaSkipReason.SOURCE_UNAVAILABLE, completed);
            delete(stagingPath);
        } catch (MoeraPermanentException | MoeraConfigurationException e) {
            boolean completed = publicationMediaRepository.fail(
                claim.publicationId(), claim.mediaId(), claim.processingToken(), e.getMessage(), null
            );
            log(claim, completed ? "permanent_failure" : "stale_claim", e.getMessage());
            delete(stagingPath);
        } catch (SourceTemporaryException e) {
            RetryOutcome outcome = retry(claim, e.retryAfter(), e.getMessage(), e);
            log(claim, outcome.result(), e.getMessage());
            if (!uploadStarted || outcome.fatal()) {
                delete(stagingPath);
            }
        } catch (MoeraTemporaryException | MoeraOutcomeUnknownException e) {
            RetryOutcome outcome = retry(claim, null, e.getMessage(), e);
            log(claim, outcome.result(), e.getMessage());
            if (!uploadStarted || outcome.fatal()) {
                delete(stagingPath);
            }
        } catch (IOException | UncheckedIOException e) {
            RetryOutcome outcome = retry(claim, null, "Cannot access staging file", e);
            log(claim, outcome.result("staging_failure"), "Cannot access staging file");
            if (!uploadStarted || outcome.fatal()) {
                delete(stagingPath);
            }
        }
    }

    private void processCompression(PublicationMediaClaim claim, PublicationMediaContext context) {
        try {
            stage(claim, "compression_check_started");
            MediaUpload upload = moeraService.checkMediaCompression(
                context.destination(), context.destinationMediaId()
            );
            stage(claim, "compression_check_finished");
            completeUpload(claim, context, upload);
        } catch (MoeraPermanentException | MoeraConfigurationException e) {
            boolean completed = publicationMediaRepository.fail(
                claim.publicationId(), claim.mediaId(), claim.processingToken(), e.getMessage(), null
            );
            log(claim, completed ? "permanent_failure" : "stale_claim", e.getMessage());
        } catch (MoeraTemporaryException | MoeraOutcomeUnknownException e) {
            RetryOutcome outcome = retry(claim, null, e.getMessage(), e);
            log(claim, outcome.result(), e.getMessage());
        }
    }

    private void completeUpload(
        PublicationMediaClaim claim,
        PublicationMediaContext context,
        MediaUpload upload
    ) {
        if (upload instanceof MediaUpload.Ready ready) {
            UploadedMedia media = ready.media();
            boolean completed = publicationMediaRepository.uploaded(
                claim.publicationId(),
                claim.mediaId(),
                claim.processingToken(),
                context.media().sourceHash(),
                media.id(),
                media.hash()
            );
            if (completed) {
                importMetrics.mediaUploaded();
            }
            log(claim, completed ? "uploaded" : "stale_claim");
        } else if (upload instanceof MediaUpload.AwaitingCompression awaiting) {
            UploadedMedia media = awaiting.original();
            boolean completed = publicationMediaRepository.awaitingCompression(
                claim.publicationId(),
                claim.mediaId(),
                claim.processingToken(),
                context.media().sourceHash(),
                media.id(),
                media.hash(),
                clock.instant().plus(workerProperties.getMediaCompressionPollInterval())
            );
            log(claim, completed ? "awaiting_compression" : "stale_claim");
        }
    }

    private void skipTooLarge(PublicationMediaClaim claim, PublicationMediaContext context) {
        boolean completed = publicationMediaRepository.skipped(
            claim.publicationId(),
            claim.mediaId(),
            claim.processingToken(),
            context.media().sourceHash(),
            MediaSkipReason.TOO_LARGE
        );
        skipped(claim, MediaSkipReason.TOO_LARGE, completed);
    }

    private RetryOutcome retry(
        PublicationMediaClaim claim,
        Duration retryAfter,
        String error,
        Throwable failure
    ) {
        RetryDecision decision = retryPolicy.failure(
            claim.attempts(), retryAfter, claim.failureSeries(), failure, clock.instant()
        );
        boolean accepted = decision.fatal()
            ? publicationMediaRepository.fail(
                claim.publicationId(), claim.mediaId(), claim.processingToken(), error, decision.series()
            )
            : publicationMediaRepository.retry(
                claim.publicationId(),
                claim.mediaId(),
                claim.processingToken(),
                decision.nextAttemptAt(),
                error,
                decision.series()
            );
        return new RetryOutcome(accepted, decision.fatal());
    }

    private boolean compressionPending(PublicationMediaContext context) {
        return context.destinationMediaId() != null
            && context.compressionPending();
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

    private void skipped(PublicationMediaClaim claim, MediaSkipReason reason, boolean completed) {
        if (completed) {
            importMetrics.mediaSkipped(reason);
        }
        log(claim, completed ? "skipped_" + reason.name().toLowerCase(java.util.Locale.ROOT) : "stale_claim");
    }

    private void log(PublicationMediaClaim claim, String result) {
        log(claim, result, null);
    }

    private void log(PublicationMediaClaim claim, String result, String error) {
        if (error == null) {
            log.info(
                "Publication media operation finished: operation=publication_media, publication_id={}, media_id={}, "
                    + "processing_token={}, attempt={}, result={}",
                claim.publicationId(), claim.mediaId(), claim.processingToken(), claim.attempts(), result
            );
        } else {
            log.info(
                "Publication media operation finished: operation=publication_media, publication_id={}, media_id={}, "
                    + "processing_token={}, attempt={}, result={}, error={}",
                claim.publicationId(), claim.mediaId(), claim.processingToken(), claim.attempts(), result, error
            );
        }
    }

    private void started(PublicationMediaClaim claim) {
        log.info(
            "Publication media operation started: operation=publication_media, publication_id={}, media_id={}, "
                + "processing_token={}, attempt={}",
            claim.publicationId(), claim.mediaId(), claim.processingToken(), claim.attempts()
        );
    }

    private void stage(PublicationMediaClaim claim, String stage) {
        log.debug(
            "Publication media operation stage: operation=publication_media, publication_id={}, media_id={}, "
                + "processing_token={}, attempt={}, stage={}",
            claim.publicationId(), claim.mediaId(), claim.processingToken(), claim.attempts(), stage
        );
    }

    private void unexpectedFailure(PublicationMediaClaim claim, Throwable failure) {
        log.error(
            "Publication media operation failed unexpectedly: operation=publication_media, publication_id={}, "
                + "media_id={}, processing_token={}, attempt={}, error_type={}, error={}",
            claim.publicationId(), claim.mediaId(), claim.processingToken(), claim.attempts(),
            failure.getClass().getSimpleName(), failure.getMessage(), failure
        );
    }

    private record RetryOutcome(boolean accepted, boolean fatal) {

        private String result() {
            return result("temporary_failure");
        }

        private String result(String retryResult) {
            return accepted ? fatal ? "permanent_failure" : retryResult : "stale_claim";
        }

    }

}
