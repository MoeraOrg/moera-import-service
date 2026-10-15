package org.moera.importer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.inject.Inject;

import org.moera.importer.model.Message;
import org.moera.importer.moera.MoeraConfigurationException;
import org.moera.importer.moera.MoeraOutcomeUnknownException;
import org.moera.importer.moera.MoeraPermanentException;
import org.moera.importer.moera.MoeraService;
import org.moera.importer.moera.MoeraTemporaryException;
import org.moera.importer.observability.ImportMetrics;
import org.moera.importer.persistence.PublicationClaim;
import org.moera.importer.persistence.PublicationContext;
import org.moera.importer.persistence.PublicationDestinationState;
import org.moera.importer.persistence.PublicationRepublishRepository;
import org.moera.importer.persistence.PublicationRepository;
import org.moera.importer.persistence.RepublishState;
import org.moera.lib.node.types.StoryAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

@Component
public class PublicationRepublishProcessor {

    private static final Logger logger = LoggerFactory.getLogger(PublicationRepublishProcessor.class);

    @Inject
    private Clock clock;

    @Inject
    private ImportMetrics importMetrics;

    @Inject
    private MoeraService moeraService;

    @Inject
    private PublicationRepublishRepository publicationRepublishRepository;

    @Inject
    private PublicationRepository publicationRepository;

    public void process(PublicationClaim claim, PublicationContext context) {
        RepublishState state = context.republish();
        List<String> uris = context.members().stream().map(PublicationContext.Member::message)
            .filter(message -> !message.deleted()).map(Message::externalSourceUri)
            .filter(uri -> !ObjectUtils.isEmpty(uri) && !uri.isBlank()).distinct().toList();
        boolean pinned = context.members().stream().map(PublicationContext.Member::message)
            .anyMatch(message -> !message.deleted() && message.pinned());
        Map<String, String> stories = new LinkedHashMap<>(context.destinationStoryIds());
        Instant requestedAt = state.requestedAt();
        List<String> lookupUris = state.lookupUris();
        if (!state.deleting() && requestedAt == null && stories.isEmpty() && !uris.isEmpty()) {
            requestedAt = clock.instant();
            lookupUris = uris;
            if (!publicationRepublishRepository.recordRequest(
                claim.publicationId(), claim.processingToken(), requestedAt, lookupUris
            )) {
                log(claim, "stale_claim");
                return;
            }
            try {
                moeraService.republish(context.destination(), state.nodeName(), state.postingId(),
                    publications(context, uris, pinned));
            } catch (MoeraTemporaryException | MoeraPermanentException | MoeraConfigurationException e) {
                publicationRepublishRepository.clearRequest(claim.publicationId(), claim.processingToken());
                throw e;
            } catch (MoeraOutcomeUnknownException e) {
                boolean accepted = await(claim, requestedAt.plus(RepublishState.MAX_WAIT), "outcome_unknown");
                if (accepted) {
                    importMetrics.publicationUnknownOutcome();
                    importMetrics.publicationFailure("outcome_unknown");
                }
                return;
            }
        }
        Instant deadline = requestedAt != null ? requestedAt.plus(RepublishState.MAX_WAIT) : null;
        boolean deleting = state.deleting() || uris.isEmpty();
        boolean discovering = deadline != null && !stories.keySet().containsAll(context.mirrorFeeds());
        if (deleting) {
            if (!publicationRepublishRepository.recordDeletion(claim.publicationId(), claim.processingToken())) {
                log(claim, "stale_claim");
                return;
            }
            for (String storyId : stories.values()) {
                moeraService.deleteStory(context.destination(), storyId);
            }
        } else if (discovering) {
            for (String storyId : stories.values()) {
                moeraService.updateStorySources(context.destination(), storyId, uris);
            }
        }
        if (discovering && clock.instant().isBefore(deadline)) {
            Map<String, String> found = moeraService.findRepublishedStories(context.destination(), state.nodeName(),
                state.postingId(), lookupUris, context.mirrorFeeds());
            mergeStories(stories, found);
            if (!publicationRepublishRepository.saveStories(claim.publicationId(), claim.processingToken(), stories)) {
                log(claim, "stale_claim");
                return;
            }
        }
        if (deleting) {
            deleteStories(claim, context, uris, stories, deadline);
            return;
        }
        if (deadline != null && !stories.keySet().containsAll(context.mirrorFeeds())) {
            updateDiscoveredStories(context, uris, stories);
            if (!clock.instant().isBefore(deadline)) {
                throw discoveryTimeout();
            }
            await(claim, deadline, "awaiting_stories");
            return;
        }
        if (requestedAt != null
            && !publicationRepublishRepository.clearRequest(claim.publicationId(), claim.processingToken())) {
            log(claim, "stale_claim");
            return;
        }
        if (discovering) {
            updateDiscoveredStories(context, uris, stories);
        } else {
            for (String storyId : stories.values()) {
                moeraService.updateStorySources(context.destination(), storyId, uris);
            }
        }
        complete(claim, requestedAt != null ? "republished" : "updated", publicationRepository.updated(
            claim.publicationId(), claim.processingToken(), claim.targetRevision(),
            new PublicationDestinationState(pinned, stories)));
    }

    private void updateDiscoveredStories(PublicationContext context, List<String> uris, Map<String, String> stories) {
        for (String storyId : stories.values()) {
            if (!context.destinationStoryIds().containsValue(storyId)) {
                moeraService.updateStorySources(context.destination(), storyId, uris);
            }
        }
    }

    private void deleteStories(
        PublicationClaim claim,
        PublicationContext context,
        List<String> uris,
        Map<String, String> stories,
        Instant deadline
    ) {
        for (String storyId : stories.values()) {
            if (!context.destinationStoryIds().containsValue(storyId)) {
                moeraService.deleteStory(context.destination(), storyId);
            }
        }
        boolean missingFeeds = deadline != null && !stories.keySet().containsAll(context.mirrorFeeds());
        if (missingFeeds && clock.instant().isBefore(deadline)) {
            await(claim, deadline, "awaiting_story_deletion");
            return;
        }
        if (uris.isEmpty()) {
            if (missingFeeds) {
                throw discoveryTimeout();
            }
            complete(claim, "deleted", publicationRepository.deleted(claim.publicationId(), claim.processingToken(),
                claim.targetRevision(), new PublicationDestinationState(null, Map.of())));
        } else {
            boolean accepted = publicationRepublishRepository.restartAfterDeletion(
                claim.publicationId(), claim.processingToken()
            );
            if (accepted && missingFeeds) {
                importMetrics.publicationFailure("permanent");
            }
            log(claim, !accepted ? "stale_claim"
                : missingFeeds ? "discovery_expired_restart" : "deletion_completed_restart");
        }
    }

    private MoeraPermanentException discoveryTimeout() {
        return new MoeraPermanentException("Republished Moera stories were not found within 30 minutes", null);
    }

    private void mergeStories(Map<String, String> stories, Map<String, String> found) {
        found.forEach((feed, storyId) -> {
            String previous = stories.putIfAbsent(feed, storyId);
            if (previous != null && !previous.equals(storyId)) {
                throw new MoeraPermanentException("Republished story identity changed during discovery", null);
            }
        });
    }

    private List<StoryAttributes> publications(PublicationContext context, List<String> uris, boolean pinned) {
        long publishAt = context.members().stream().map(PublicationContext.Member::message)
            .map(Message::publishedAt).min(Instant::compareTo).orElseThrow().getEpochSecond();
        return context.mirrorFeeds().stream().map(feed -> {
            StoryAttributes attributes = new StoryAttributes();
            attributes.setFeedName(feed);
            attributes.setPublishAt(publishAt);
            attributes.setPinned(pinned);
            attributes.setExternalSourceUri(uris);
            return attributes;
        }).toList();
    }

    private boolean await(PublicationClaim claim, Instant deadline, String result) {
        Instant retryAt = clock.instant().plus(RepublishState.POLL_INTERVAL);
        if (retryAt.isAfter(deadline)) {
            retryAt = deadline;
        }
        boolean accepted = publicationRepublishRepository.awaitStories(
            claim.publicationId(), claim.processingToken(), retryAt
        );
        log(claim, accepted ? result : "stale_claim");
        return accepted;
    }

    private void complete(PublicationClaim claim, String operation, boolean accepted) {
        if (accepted) {
            importMetrics.publicationCompleted(operation);
        }
        log(claim, accepted ? operation : "stale_claim");
    }

    private void log(PublicationClaim claim, String result) {
        logger.info(
            "Publication operation finished: operation=publication_republish, publication_id={}, "
                + "processing_token={}, attempt={}, target_revision={}, result={}",
            claim.publicationId(), claim.processingToken(), claim.attempts(), claim.targetRevision(), result
        );
    }

}
