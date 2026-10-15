package org.moera.importer.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moera.importer.model.BodyFormat;
import org.moera.importer.model.Message;
import org.moera.importer.source.ForwardAttribution;
import org.moera.importer.source.SourceMedia;
import org.moera.importer.source.SourceMessage;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.moera.importer.persistence.jooq.enums.AccessState.GRANTED;
import static org.moera.importer.persistence.jooq.enums.SourceType.TELEGRAM;
import static org.moera.importer.persistence.jooq.enums.SubscriptionState.ACTIVE;
import static org.moera.importer.persistence.jooq.tables.Channels.CHANNELS;
import static org.moera.importer.persistence.jooq.tables.Destinations.DESTINATIONS;
import static org.moera.importer.persistence.jooq.tables.Messages.MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Mirrors.MIRRORS;
import static org.moera.importer.persistence.jooq.tables.PublicationMedia.PUBLICATION_MEDIA;
import static org.moera.importer.persistence.jooq.tables.PublicationMessages.PUBLICATION_MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Publications.PUBLICATIONS;

@SpringBootTest
class PublicationRepublishRepositoryTest extends PostgresIntegrationTest {

    private static final String ORIGINAL_CHAT = "-1001234567890";
    private static final Instant TIME = Instant.parse("2026-10-01T12:00:00Z");

    @Inject
    private DSLContext dslContext;

    @Inject
    private MessageRepository messageRepository;

    @Inject
    private PublicationRepository publicationRepository;

    private long originalChannel;
    private long forwardingChannel;
    private long originalMirror;
    private long forwardingMirror;
    private long originalPublication;
    private long originalFirst;
    private long originalSecond;

    @BeforeEach
    void setUp() {
        dslContext.truncate(CHANNELS).restartIdentity().cascade().execute();
        dslContext.truncate(DESTINATIONS).restartIdentity().cascade().execute();
        originalChannel = channel(ORIGINAL_CHAT, "original");
        forwardingChannel = channel("-1009876543210", "forwarding");
        long destination = destination("original_0");
        originalMirror = mirror(originalChannel, destination);
        forwardingMirror = mirror(forwardingChannel, destination("forwarding_0"));
        originalFirst = ingest(originalChannel, 1, null, false, "Original 1", false);
        originalSecond = ingest(originalChannel, 2, null, false, "Original 2", false);
        publicationRepository.create(originalMirror, messages(originalMirror));
        originalPublication = publication(originalMirror);
        dslContext.update(PUBLICATIONS)
            .set(PUBLICATIONS.DESTINATION_POST_ID, "original-post")
            .set(PUBLICATIONS.PUBLISHED_REVISION, 0)
            .setNull(PUBLICATIONS.NEXT_ATTEMPT_AT)
            .where(PUBLICATIONS.ID.eq(originalPublication)).execute();
    }

    @Test
    void republishesKnownOriginalAndSkipsUploadingForwardedMedia() {
        ingest(forwardingChannel, 10, 1L, false, "Forwarded", true);

        publicationRepository.create(forwardingMirror, messages(forwardingMirror));

        long owner = publication(forwardingMirror);
        assertThat(column(owner, "republish_node_name")).isEqualTo("original_0");
        assertThat(column(owner, "republish_post_id")).isEqualTo("original-post");
        assertThat(dslContext.fetchCount(PUBLICATION_MEDIA, PUBLICATION_MEDIA.PUBLICATION_ID.eq(owner))).isZero();
        assertThat(dslContext.fetch("select message_id from publication_original_messages where publication_id = ?",
            owner).getValues(0, Long.class)).containsExactlyInAnyOrder(originalFirst, originalSecond);
        assertThat(publicationRepository.claimReady(10, Duration.ofMinutes(1)))
            .singleElement().extracting(PublicationClaim::publicationId).isEqualTo(owner);
    }

    @Test
    void lateForwardOfAnotherOriginalMemberReusesOwnerWithoutChangingMembership() {
        long first = ingest(forwardingChannel, 10, 1L, false, "First forward", false);
        publicationRepository.create(forwardingMirror, messages(forwardingMirror));
        long owner = publication(forwardingMirror);
        long second = ingest(forwardingChannel, 11, 2L, false, "Second forward", true);

        publicationRepository.create(forwardingMirror, messages(forwardingMirror));

        assertThat(dslContext.select(PUBLICATION_MESSAGES.MESSAGE_ID).from(PUBLICATION_MESSAGES)
            .where(PUBLICATION_MESSAGES.PUBLICATION_ID.eq(owner)).fetch(PUBLICATION_MESSAGES.MESSAGE_ID))
            .containsExactly(first);
        long linked = dslContext.select(PUBLICATIONS.ID).from(PUBLICATIONS)
            .where(PUBLICATIONS.MIRROR_ID.eq(forwardingMirror)).and(PUBLICATIONS.ID.ne(owner))
            .fetchSingle(PUBLICATIONS.ID);
        assertThat(column(linked, "republish_owner_id")).isEqualTo(Long.toString(owner));
        PublicationContext context = publicationRepository.loadContext(owner, 1);
        assertThat(context.members()).extracting(member -> member.message().id()).containsExactly(first, second);
        assertThat(dslContext.fetchCount(PUBLICATION_MEDIA)).isZero();
        assertThat(publicationRepository.claimReady(10, Duration.ofMinutes(1)))
            .singleElement().extracting(PublicationClaim::publicationId).isEqualTo(owner);
    }

    @Test
    void fallsBackToAnOrdinaryCopyWhenOriginalHasNotBeenDelivered() {
        dslContext.update(PUBLICATIONS).setNull(PUBLICATIONS.DESTINATION_POST_ID)
            .where(PUBLICATIONS.ID.eq(originalPublication)).execute();
        ingest(forwardingChannel, 10, 1L, false, "Forward", true);

        publicationRepository.create(forwardingMirror, messages(forwardingMirror));

        long publicationId = publication(forwardingMirror);
        assertThat(column(publicationId, "republish_node_name")).isNull();
        assertThat(dslContext.fetchCount(PUBLICATION_MEDIA, PUBLICATION_MEDIA.PUBLICATION_ID.eq(publicationId)))
            .isEqualTo(1);
    }

    @Test
    void splitsGroupedForwardsOfDifferentOriginalPostsAndUnknownMessages() {
        long third = ingest(originalChannel, 3, null, false, "Other post", false);
        publicationRepository.create(originalMirror, messages(originalMirror));
        dslContext.update(PUBLICATIONS).set(PUBLICATIONS.DESTINATION_POST_ID, "other-post")
            .where(PUBLICATIONS.MIRROR_ID.eq(originalMirror)).and(PUBLICATIONS.ID.ne(originalPublication)).execute();
        ingest(forwardingChannel, 10, 1L, false, "First", false);
        ingest(forwardingChannel, 11, 3L, false, "Other", false);
        ingest(forwardingChannel, 12, 404L, false, "Unknown", false);

        publicationRepository.create(forwardingMirror, messages(forwardingMirror));

        assertThat(dslContext.fetchCount(PUBLICATIONS, PUBLICATIONS.MIRROR_ID.eq(forwardingMirror))).isEqualTo(3);
        assertThat(third).isPositive();
        assertThat(messageRepository.findUnassignedForMirror(forwardingMirror)).isEmpty();
    }

    @Test
    void forwardBodyAndMediaEditsDoNotReviseOwnerOrScheduleMediaButDeletionDoes() {
        ingest(forwardingChannel, 10, 1L, false, "Forward", true);
        publicationRepository.create(forwardingMirror, messages(forwardingMirror));
        long owner = publication(forwardingMirror);
        ingest(forwardingChannel, 10, 1L, false, "Edited forward", true);

        assertThat(dslContext.selectFrom(PUBLICATIONS).where(PUBLICATIONS.ID.eq(owner)).fetchSingle().getRevision())
            .isZero();
        assertThat(dslContext.fetchCount(PUBLICATION_MEDIA)).isZero();

        messageRepository.markDeleted(forwardingChannel, Long.toString(10L << 20));

        assertThat(dslContext.selectFrom(PUBLICATIONS).where(PUBLICATIONS.ID.eq(owner)).fetchSingle().getRevision())
            .isEqualTo(1);
    }

    @Test
    void deletionOfALinkedForwardRevisesOnlyTheOwner() {
        ingest(forwardingChannel, 10, 1L, false, "First", false);
        publicationRepository.create(forwardingMirror, messages(forwardingMirror));
        long owner = publication(forwardingMirror);
        ingest(forwardingChannel, 11, 2L, false, "Second", false);
        publicationRepository.create(forwardingMirror, messages(forwardingMirror));
        messageRepository.markDeleted(forwardingChannel, Long.toString(11L << 20));

        PublicationContext context = publicationRepository.loadContext(owner, 2);
        assertThat(context.members().getLast().message().deleted()).isTrue();
        assertThat(publicationRepository.claimReady(10, Duration.ofMinutes(1)))
            .singleElement().extracting(PublicationClaim::publicationId).isEqualTo(owner);
    }

    @Test
    void resolvesLegacyForwardByExactPublicMessageUri() {
        long id = ingest(forwardingChannel, 10, 1L, false, "Legacy", false);
        dslContext.update(MESSAGES).set(MESSAGES.SOURCE_DATA, org.jooq.JSON.valueOf("{}"))
            .where(MESSAGES.ID.eq(id)).execute();

        publicationRepository.create(forwardingMirror, messages(forwardingMirror));

        assertThat(column(publication(forwardingMirror), "republish_post_id")).isEqualTo("original-post");
    }

    private String column(long publicationId, String name) {
        return dslContext.fetchOne("select to_jsonb(p) ->> ? from publications p where id = ?",
            name, publicationId).get(0, String.class);
    }

    private long channel(String sourceId, String name) {
        return dslContext.insertInto(CHANNELS).set(CHANNELS.SOURCE_TYPE, TELEGRAM)
            .set(CHANNELS.SOURCE_ID, sourceId).set(CHANNELS.PUBLIC_USERNAME, name)
            .set(CHANNELS.SOURCE_URI, "https://t.me/" + name)
            .set(CHANNELS.ACCESS_STATE, GRANTED).set(CHANNELS.SUBSCRIPTION_STATE, ACTIVE)
            .returning(CHANNELS.ID).fetchSingle().getId();
    }

    private long destination(String name) {
        return dslContext.insertInto(DESTINATIONS).set(DESTINATIONS.NODE_NAME, name)
            .set(DESTINATIONS.PROFILE_SYNC, false).returning(DESTINATIONS.ID).fetchSingle().getId();
    }

    private long mirror(long channelId, long destinationId) {
        return dslContext.insertInto(MIRRORS).set(MIRRORS.CHANNEL_ID, channelId)
            .set(MIRRORS.DESTINATION_ID, destinationId).set(MIRRORS.FEEDS, new String[] { "timeline" })
            .returning(MIRRORS.ID).fetchSingle().getId();
    }

    private long ingest(long channelId, long publicId, Long originalId, boolean deleted, String body, boolean media) {
        var data = JsonNodeFactory.instance.objectNode();
        if (originalId != null) {
            data.put("forwardOriginChatId", ORIGINAL_CHAT);
            data.put("forwardOriginMessageId", Long.toString(originalId << 20));
        }
        String username = channelId == originalChannel ? "original" : "forwarding";
        return messageRepository.ingest(channelId, new SourceMessage(
            Long.toString(publicId << 20), deleted, false, TIME.plusSeconds(publicId), body, BodyFormat.HTML,
            org.moera.importer.application.Sha256.digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "https://t.me/" + username + "/" + publicId,
            originalId != null ? new ForwardAttribution("Original", "https://t.me/original/" + originalId) : null,
            data, media ? List.of(new SourceMedia("media-" + publicId, 0, "image/png", "image.png", 10L,
                new byte[32], JsonNodeFactory.instance.objectNode())) : List.of(), List.of()
        )).messageId();
    }

    private List<Message> messages(long mirrorId) {
        return messageRepository.findUnassignedForMirror(mirrorId);
    }

    private long publication(long mirrorId) {
        return dslContext.select(PUBLICATIONS.ID).from(PUBLICATIONS)
            .where(PUBLICATIONS.MIRROR_ID.eq(mirrorId)).fetchSingle(PUBLICATIONS.ID);
    }

}
