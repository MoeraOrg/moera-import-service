package org.moera.importer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.moera.importer.config.CreateRateProperties;
import org.moera.importer.moera.CreatedPosting;
import org.moera.importer.moera.MoeraConfigurationException;
import org.moera.importer.moera.MoeraOutcomeUnknownException;
import org.moera.importer.moera.MoeraPermanentException;
import org.moera.importer.moera.MoeraService;
import org.moera.importer.moera.MoeraTemporaryException;
import org.moera.importer.observability.ImportMetrics;
import org.moera.importer.persistence.DestinationCreateSlot;
import org.moera.importer.persistence.DestinationRepository;
import org.moera.importer.persistence.PublicationClaim;
import org.moera.importer.persistence.PublicationContext;
import org.moera.importer.persistence.PublicationRepository;
import org.moera.importer.persistence.PublicationSnapshotChangedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PublicationProcessor {

    private static final Logger logger = LoggerFactory.getLogger(PublicationProcessor.class);

    @Inject
    private Clock clock;

    @Inject
    private CreateRateProperties createRateProperties;

    @Inject
    private DestinationRepository destinationRepository;

    @Inject
    private MoeraService moeraService;

    @Inject
    private ImportMetrics importMetrics;

    @Inject
    private PublicationPayloadBuilder publicationPayloadBuilder;

    @Inject
    private PublicationRepository publicationRepository;

    @Inject
    private RetryPolicy retryPolicy;

    public void process(PublicationClaim claim) {
        try {
            PublicationContext context = publicationRepository.loadContext(
                claim.publicationId(), claim.targetRevision()
            );
            PublicationPayload payload = publicationPayloadBuilder.build(
                context, moeraService.capabilities(context.destination())
            );
            if (context.destinationPostId() == null) {
                createOrCompleteEmpty(claim, context, payload);
            } else {
                updateOrDelete(claim, context, payload);
            }
        } catch (PublicationSnapshotChangedException e) {
            log(claim, "revision_changed");
            publicationRepository.retry(
                claim.publicationId(), claim.processingToken(), clock.instant(), null
            );
        } catch (MoeraOutcomeUnknownException e) {
            importMetrics.publicationUnknownOutcome();
            importMetrics.publicationFailure("outcome_unknown");
            log(claim, "outcome_unknown", e.getMessage());
            retry(claim, e.getMessage());
        } catch (MoeraTemporaryException e) {
            importMetrics.publicationFailure("temporary");
            log(claim, "temporary_failure", e.getMessage());
            retry(claim, e.getMessage());
        } catch (MoeraPermanentException | MoeraConfigurationException e) {
            importMetrics.publicationFailure("permanent");
            log(claim, "permanent_failure", e.getMessage());
            publicationRepository.fail(
                claim.publicationId(), claim.processingToken(), e.getMessage()
            );
        }
    }

    private void createOrCompleteEmpty(
        PublicationClaim claim,
        PublicationContext context,
        PublicationPayload payload
    ) {
        if (payload.empty()) {
            boolean completed = publicationRepository.deleted(
                claim.publicationId(),
                claim.processingToken(),
                claim.targetRevision(),
                JsonNodeFactory.instance.objectNode()
            );
            complete(claim, "deleted", completed);
            return;
        }
        DestinationCreateSlot slot = destinationRepository.acquireCreateSlot(
            context.destination().id(), createRateProperties.interval()
        );
        if (slot instanceof DestinationCreateSlot.Deferred(Instant retryAt)) {
            boolean accepted = publicationRepository.deferCreateRate(
                claim.publicationId(), claim.processingToken(), retryAt
            );
            if (accepted) {
                importMetrics.publicationRateLimited();
            }
            log(claim, accepted ? "rate_limited" : "stale_claim");
            return;
        }
        CreatedPosting created = moeraService.createPost(context.destination(), payload);
        boolean completed = publicationRepository.created(
            claim.publicationId(),
            claim.processingToken(),
            claim.targetRevision(),
            created.postingId(),
            createdData(created, payload.pinned())
        );
        complete(claim, "created", completed);
    }

    private void updateOrDelete(
        PublicationClaim claim,
        PublicationContext context,
        PublicationPayload payload
    ) {
        if (payload.empty()) {
            moeraService.deletePost(context.destination(), context.destinationPostId());
            boolean completed = publicationRepository.deleted(
                claim.publicationId(),
                claim.processingToken(),
                claim.targetRevision(),
                JsonNodeFactory.instance.objectNode()
            );
            complete(claim, "deleted", completed);
            return;
        }
        moeraService.updatePost(context.destination(), context.destinationPostId(), payload);
        ObjectNode destinationData = synchronizePin(context, payload.pinned());
        boolean completed = publicationRepository.updated(
            claim.publicationId(), claim.processingToken(), claim.targetRevision(), destinationData
        );
        complete(claim, "updated", completed);
    }

    private ObjectNode synchronizePin(PublicationContext context, boolean pinned) {
        ObjectNode destinationData = context.destinationData() instanceof ObjectNode objectNode
            ? objectNode.deepCopy()
            : JsonNodeFactory.instance.objectNode();
        JsonNode deliveredPin = destinationData.get("pinned");
        if (deliveredPin == null || !deliveredPin.isBoolean() || deliveredPin.booleanValue() != pinned) {
            JsonNode storyIds = destinationData.path("storyIds");
            if (storyIds.isObject()) {
                for (Map.Entry<String, JsonNode> story : storyIds.properties()) {
                    if (story.getValue().isTextual() && !story.getValue().textValue().isBlank()) {
                        moeraService.updateStory(
                            context.destination(), story.getValue().textValue(), pinned
                        );
                    }
                }
            }
            destinationData.put("pinned", pinned);
        }
        return destinationData;
    }

    private ObjectNode createdData(CreatedPosting created, boolean pinned) {
        ObjectNode destinationData = JsonNodeFactory.instance.objectNode().put("pinned", pinned);
        ObjectNode storyIds = destinationData.putObject("storyIds");
        created.storyIds().forEach(storyIds::put);
        return destinationData;
    }

    private void retry(PublicationClaim claim, String error) {
        Instant nextAttemptAt = clock.instant().plus(retryPolicy.delay(claim.attempts(), null));
        publicationRepository.retry(
            claim.publicationId(), claim.processingToken(), nextAttemptAt, error
        );
    }

    private void complete(PublicationClaim claim, String operation, boolean completed) {
        String result = completed ? "success" : "stale_claim";
        if (completed) {
            importMetrics.publicationCompleted(operation);
        }
        log(claim, result);
    }

    private void log(PublicationClaim claim, String result) {
        log(claim, result, null);
    }

    private void log(PublicationClaim claim, String result, String error) {
        if (error == null) {
            logger.info(
                "Publication operation finished: operation=publication_delivery, publication_id={}, "
                    + "processing_token={}, attempt={}, target_revision={}, result={}",
                claim.publicationId(), claim.processingToken(), claim.attempts(), claim.targetRevision(), result
            );
        } else {
            logger.info(
                "Publication operation finished: operation=publication_delivery, publication_id={}, "
                    + "processing_token={}, attempt={}, target_revision={}, result={}, error={}",
                claim.publicationId(), claim.processingToken(), claim.attempts(), claim.targetRevision(), result, error
            );
        }
    }

}
