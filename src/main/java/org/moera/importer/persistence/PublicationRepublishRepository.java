package org.moera.importer.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import jakarta.inject.Inject;

import org.jooq.DSLContext;
import org.moera.importer.model.Message;
import org.moera.importer.source.telegram.TelegramMessageUrl;
import org.moera.importer.source.telegram.TelegramForwardAttribution;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;

import static org.jooq.impl.DSL.when;
import static org.moera.importer.persistence.jooq.enums.SourceType.TELEGRAM;
import static org.moera.importer.persistence.jooq.tables.Channels.CHANNELS;
import static org.moera.importer.persistence.jooq.tables.Destinations.DESTINATIONS;
import static org.moera.importer.persistence.jooq.tables.Messages.MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Mirrors.MIRRORS;
import static org.moera.importer.persistence.jooq.tables.PublicationMessages.PUBLICATION_MESSAGES;
import static org.moera.importer.persistence.jooq.tables.PublicationOriginalMessages.PUBLICATION_ORIGINAL_MESSAGES;
import static org.moera.importer.persistence.jooq.tables.PublicationStories.PUBLICATION_STORIES;
import static org.moera.importer.persistence.jooq.tables.Publications.PUBLICATIONS;

@Repository
public class PublicationRepublishRepository {

    @Inject
    private Clock clock;

    @Inject
    private DSLContext dslContext;

    @Inject
    private JooqMappings jooqMappings;

    @Inject
    private PublicationLinkRepository publicationLinkRepository;

    public List<Group> groups(long mirrorId, List<Message> messages) {
        List<Group> groups = new ArrayList<>();
        List<Message> members = new ArrayList<>();
        Target previous = null;
        for (Message message : messages) {
            Target target = findTarget(mirrorId, message);
            if (!members.isEmpty() && !Objects.equals(previous, target)) {
                groups.add(new Group(List.copyOf(members), previous));
                members.clear();
            }
            members.add(message);
            previous = target;
        }
        if (!members.isEmpty()) {
            groups.add(new Group(List.copyOf(members), previous));
        }
        return List.copyOf(groups);
    }

    private Target findTarget(long mirrorId, Message message) {
        if (ObjectUtils.isEmpty(message.externalSourceUri()) || message.externalSourceUri().isBlank()) {
            return null;
        }
        Long originalId = originalMessage(message);
        if (originalId == null) {
            return null;
        }
        var owner = dslContext.select(PUBLICATIONS.ID, PUBLICATIONS.REPUBLISH_NODE_NAME, PUBLICATIONS.REPUBLISH_POST_ID)
            .from(PUBLICATION_ORIGINAL_MESSAGES)
            .join(PUBLICATIONS).on(PUBLICATIONS.ID.eq(PUBLICATION_ORIGINAL_MESSAGES.PUBLICATION_ID))
            .where(PUBLICATION_ORIGINAL_MESSAGES.MESSAGE_ID.eq(originalId))
            .and(PUBLICATIONS.MIRROR_ID.eq(mirrorId))
            .orderBy(PUBLICATIONS.ID).limit(1).fetchOne();
        if (owner != null) {
            return new Target(owner.get(PUBLICATIONS.REPUBLISH_NODE_NAME), owner.get(PUBLICATIONS.REPUBLISH_POST_ID),
                originalMembers(owner.get(PUBLICATIONS.ID), true));
        }
        Long destinationId = dslContext.select(MIRRORS.DESTINATION_ID).from(MIRRORS)
            .where(MIRRORS.ID.eq(mirrorId)).fetchSingle(MIRRORS.DESTINATION_ID);
        var original = dslContext.select(PUBLICATIONS.ID, DESTINATIONS.NODE_NAME, PUBLICATIONS.DESTINATION_POST_ID)
            .from(PUBLICATION_MESSAGES)
            .join(PUBLICATIONS).on(PUBLICATIONS.ID.eq(PUBLICATION_MESSAGES.PUBLICATION_ID))
            .join(MIRRORS).on(MIRRORS.ID.eq(PUBLICATIONS.MIRROR_ID))
            .join(DESTINATIONS).on(DESTINATIONS.ID.eq(MIRRORS.DESTINATION_ID))
            .where(PUBLICATION_MESSAGES.MESSAGE_ID.eq(originalId))
            .and(PUBLICATIONS.DESTINATION_POST_ID.isNotNull())
            .orderBy(when(DESTINATIONS.ID.eq(destinationId), 0).otherwise(1), DESTINATIONS.ID, PUBLICATIONS.ID)
            .limit(1).fetchOne();
        return original == null ? null : new Target(original.get(DESTINATIONS.NODE_NAME),
            original.get(PUBLICATIONS.DESTINATION_POST_ID), originalMembers(original.get(PUBLICATIONS.ID), false));
    }

    private Long originalMessage(Message message) {
        var origin = TelegramForwardAttribution.channelOrigin(message.sourceData());
        if (origin != null) {
            return dslContext.select(MESSAGES.ID).from(MESSAGES)
                .join(CHANNELS).on(CHANNELS.ID.eq(MESSAGES.CHANNEL_ID))
                .where(CHANNELS.SOURCE_TYPE.eq(TELEGRAM)).and(CHANNELS.SOURCE_ID.eq(origin.chatId()))
                .and(MESSAGES.SOURCE_ID.eq(origin.messageId())).fetchOne(MESSAGES.ID);
        }
        if (message.forwardAttribution() == null) {
            return null;
        }
        var url = TelegramMessageUrl.parse(message.forwardAttribution().uri()).orElse(null);
        if (url == null) {
            return null;
        }
        var targets = publicationLinkRepository.findTargets(Set.of(url)).getOrDefault(url, List.of());
        return targets.isEmpty() ? null : targets.getFirst().messageId();
    }

    private List<Long> originalMembers(long publicationId, boolean saved) {
        if (saved) {
            return dslContext.select(PUBLICATION_ORIGINAL_MESSAGES.MESSAGE_ID).from(PUBLICATION_ORIGINAL_MESSAGES)
                .where(PUBLICATION_ORIGINAL_MESSAGES.PUBLICATION_ID.eq(publicationId))
                .orderBy(PUBLICATION_ORIGINAL_MESSAGES.MESSAGE_ID).fetch(PUBLICATION_ORIGINAL_MESSAGES.MESSAGE_ID);
        }
        return dslContext.select(PUBLICATION_MESSAGES.MESSAGE_ID).from(PUBLICATION_MESSAGES)
            .where(PUBLICATION_MESSAGES.PUBLICATION_ID.eq(publicationId))
            .orderBy(PUBLICATION_MESSAGES.MESSAGE_ID).fetch(PUBLICATION_MESSAGES.MESSAGE_ID);
    }

    public void initialize(long publicationId, long mirrorId, Target target) {
        Long ownerId = dslContext.select(PUBLICATIONS.ID).from(PUBLICATIONS)
            .where(PUBLICATIONS.MIRROR_ID.eq(mirrorId))
            .and(PUBLICATIONS.REPUBLISH_NODE_NAME.eq(target.nodeName()))
            .and(PUBLICATIONS.REPUBLISH_POST_ID.eq(target.postingId()))
            .forUpdate().fetchOne(PUBLICATIONS.ID);
        if (ownerId == null) {
            dslContext.update(PUBLICATIONS).set(PUBLICATIONS.REPUBLISH_NODE_NAME, target.nodeName())
                .set(PUBLICATIONS.REPUBLISH_POST_ID, target.postingId())
                .where(PUBLICATIONS.ID.eq(publicationId)).execute();
            target.originalMessages().forEach(messageId -> dslContext.insertInto(PUBLICATION_ORIGINAL_MESSAGES)
                .set(PUBLICATION_ORIGINAL_MESSAGES.PUBLICATION_ID, publicationId)
                .set(PUBLICATION_ORIGINAL_MESSAGES.MESSAGE_ID, messageId).execute());
        } else {
            dslContext.update(PUBLICATIONS).set(PUBLICATIONS.REPUBLISH_OWNER_ID, ownerId)
                .set(PUBLICATIONS.PUBLISHED_REVISION, 0).setNull(PUBLICATIONS.NEXT_ATTEMPT_AT)
                .where(PUBLICATIONS.ID.eq(publicationId)).execute();
            revise(List.of(ownerId));
        }
    }

    public void revise(List<Long> publicationIds) {
        if (publicationIds.isEmpty()) {
            return;
        }
        dslContext.update(PUBLICATIONS).set(PUBLICATIONS.REVISION, PUBLICATIONS.REVISION.plus(1))
            .set(PUBLICATIONS.ATTEMPTS, 0).setNull(PUBLICATIONS.LAST_ERROR)
            .setNull(PUBLICATIONS.FAILURE_TYPE).setNull(PUBLICATIONS.FAILURE_STARTED_AT)
            .set(PUBLICATIONS.NEXT_ATTEMPT_AT, jooqMappings.offsetDateTime(clock.instant()))
            .set(PUBLICATIONS.UPDATED_AT, jooqMappings.offsetDateTime(clock.instant()))
            .where(PUBLICATIONS.ID.in(publicationIds)).execute();
    }

    public boolean recordRequest(long publicationId, UUID token, Instant requestedAt, List<String> uris) {
        return dslContext.update(PUBLICATIONS)
            .set(PUBLICATIONS.REPUBLISH_REQUESTED_AT, jooqMappings.offsetDateTime(requestedAt))
            .set(PUBLICATIONS.REPUBLISH_LOOKUP_URIS, uris.toArray(String[]::new))
            .where(PUBLICATIONS.ID.eq(publicationId)).and(PUBLICATIONS.PROCESSING_TOKEN.eq(token))
            .and(PUBLICATIONS.REPUBLISH_REQUESTED_AT.isNull()).execute() == 1;
    }

    public boolean clearRequest(long publicationId, UUID token) {
        return dslContext.update(PUBLICATIONS).setNull(PUBLICATIONS.REPUBLISH_REQUESTED_AT)
            .set(PUBLICATIONS.REPUBLISH_LOOKUP_URIS, new String[0])
            .where(PUBLICATIONS.ID.eq(publicationId)).and(PUBLICATIONS.PROCESSING_TOKEN.eq(token)).execute() == 1;
    }

    public boolean recordDeletion(long publicationId, UUID token) {
        return dslContext.update(PUBLICATIONS).set(PUBLICATIONS.REPUBLISH_DELETING, true)
            .where(PUBLICATIONS.ID.eq(publicationId)).and(PUBLICATIONS.PROCESSING_TOKEN.eq(token)).execute() == 1;
    }

    @Transactional
    public boolean restartAfterDeletion(long publicationId, UUID token) {
        boolean accepted = dslContext.update(PUBLICATIONS).set(PUBLICATIONS.REPUBLISH_DELETING, false)
            .setNull(PUBLICATIONS.REPUBLISH_REQUESTED_AT).set(PUBLICATIONS.REPUBLISH_LOOKUP_URIS, new String[0])
            .setNull(PUBLICATIONS.DESTINATION_PINNED)
            .set(PUBLICATIONS.ATTEMPTS, 0).setNull(PUBLICATIONS.LAST_ERROR)
            .setNull(PUBLICATIONS.FAILURE_TYPE).setNull(PUBLICATIONS.FAILURE_STARTED_AT)
            .set(PUBLICATIONS.NEXT_ATTEMPT_AT, jooqMappings.offsetDateTime(clock.instant()))
            .setNull(PUBLICATIONS.PROCESSING_TOKEN).setNull(PUBLICATIONS.PROCESSING_UNTIL)
            .set(PUBLICATIONS.UPDATED_AT, jooqMappings.offsetDateTime(clock.instant()))
            .where(PUBLICATIONS.ID.eq(publicationId)).and(PUBLICATIONS.PROCESSING_TOKEN.eq(token)).execute() == 1;
        if (accepted) {
            dslContext.deleteFrom(PUBLICATION_STORIES).where(PUBLICATION_STORIES.PUBLICATION_ID.eq(publicationId))
                .execute();
        }
        return accepted;
    }

    @Transactional
    public boolean saveStories(long publicationId, UUID token, Map<String, String> stories) {
        if (dslContext.select(PUBLICATIONS.ID).from(PUBLICATIONS)
            .where(PUBLICATIONS.ID.eq(publicationId)).and(PUBLICATIONS.PROCESSING_TOKEN.eq(token))
            .forUpdate().fetchOptional().isEmpty()) {
            return false;
        }
        stories.forEach((feed, story) -> dslContext.insertInto(PUBLICATION_STORIES)
            .set(PUBLICATION_STORIES.PUBLICATION_ID, publicationId)
            .set(PUBLICATION_STORIES.FEED_NAME, feed).set(PUBLICATION_STORIES.STORY_ID, story)
            .onConflict(PUBLICATION_STORIES.PUBLICATION_ID, PUBLICATION_STORIES.FEED_NAME)
            .doUpdate().set(PUBLICATION_STORIES.STORY_ID, story).execute());
        return true;
    }

    public boolean awaitStories(long publicationId, UUID token, Instant nextAttemptAt) {
        return dslContext.update(PUBLICATIONS)
            .set(PUBLICATIONS.NEXT_ATTEMPT_AT, jooqMappings.offsetDateTime(nextAttemptAt))
            .setNull(PUBLICATIONS.PROCESSING_TOKEN).setNull(PUBLICATIONS.PROCESSING_UNTIL)
            .set(PUBLICATIONS.UPDATED_AT, jooqMappings.offsetDateTime(clock.instant()))
            .where(PUBLICATIONS.ID.eq(publicationId)).and(PUBLICATIONS.PROCESSING_TOKEN.eq(token)).execute() == 1;
    }

    public Instant limitRetry(long publicationId, Instant retryAt) {
        var requestedAt = dslContext.select(PUBLICATIONS.REPUBLISH_REQUESTED_AT).from(PUBLICATIONS)
            .where(PUBLICATIONS.ID.eq(publicationId)).fetchOne(PUBLICATIONS.REPUBLISH_REQUESTED_AT);
        if (requestedAt == null) {
            return retryAt;
        }
        Instant deadline = requestedAt.toInstant().plus(RepublishState.MAX_WAIT);
        if (!clock.instant().isBefore(deadline)) {
            return retryAt;
        }
        return retryAt.isAfter(deadline) ? deadline : retryAt;
    }

    public record Target(String nodeName, String postingId, List<Long> originalMessages) {
    }

    public record Group(List<Message> messages, Target target) {
    }

}
