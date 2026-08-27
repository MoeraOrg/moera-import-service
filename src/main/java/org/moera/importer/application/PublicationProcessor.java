package org.moera.importer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.moera.importer.moera.CreatedPosting;
import org.moera.importer.moera.MoeraConfigurationException;
import org.moera.importer.moera.MoeraOutcomeUnknownException;
import org.moera.importer.moera.MoeraPermanentException;
import org.moera.importer.moera.MoeraService;
import org.moera.importer.moera.MoeraTemporaryException;
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
    private MoeraService moeraService;

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
            publicationRepository.retry(
                claim.publicationId(), claim.processingToken(), clock.instant(), null
            );
        } catch (MoeraOutcomeUnknownException e) {
            logger.warn("Moera publication mutation outcome is unknown; scheduling a retry");
            retry(claim, e.getMessage());
        } catch (MoeraTemporaryException e) {
            retry(claim, e.getMessage());
        } catch (MoeraPermanentException | MoeraConfigurationException e) {
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
            publicationRepository.deleted(
                claim.publicationId(),
                claim.processingToken(),
                claim.targetRevision(),
                JsonNodeFactory.instance.objectNode()
            );
            return;
        }
        CreatedPosting created = moeraService.createPost(context.destination(), payload);
        publicationRepository.created(
            claim.publicationId(),
            claim.processingToken(),
            claim.targetRevision(),
            created.postingId(),
            createdData(created, payload.pinned())
        );
    }

    private void updateOrDelete(
        PublicationClaim claim,
        PublicationContext context,
        PublicationPayload payload
    ) {
        if (payload.empty()) {
            moeraService.deletePost(context.destination(), context.destinationPostId());
            publicationRepository.deleted(
                claim.publicationId(),
                claim.processingToken(),
                claim.targetRevision(),
                JsonNodeFactory.instance.objectNode()
            );
            return;
        }
        moeraService.updatePost(context.destination(), context.destinationPostId(), payload);
        ObjectNode destinationData = synchronizePin(context, payload.pinned());
        publicationRepository.updated(
            claim.publicationId(), claim.processingToken(), claim.targetRevision(), destinationData
        );
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

}
