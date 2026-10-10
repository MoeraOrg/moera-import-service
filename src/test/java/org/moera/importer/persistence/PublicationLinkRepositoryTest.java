package org.moera.importer.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import jakarta.inject.Inject;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moera.importer.persistence.PublicationLinkRepository.PostingTarget;
import org.moera.importer.source.telegram.TelegramMessageUrl;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.moera.importer.persistence.jooq.enums.AccessState.GRANTED;
import static org.moera.importer.persistence.jooq.enums.SourceType.TELEGRAM;
import static org.moera.importer.persistence.jooq.enums.SubscriptionState.ACTIVE;
import static org.moera.importer.persistence.jooq.tables.Channels.CHANNELS;
import static org.moera.importer.persistence.jooq.tables.Destinations.DESTINATIONS;
import static org.moera.importer.persistence.jooq.tables.Messages.MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Mirrors.MIRRORS;
import static org.moera.importer.persistence.jooq.tables.PublicationMessages.PUBLICATION_MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Publications.PUBLICATIONS;

@SpringBootTest
class PublicationLinkRepositoryTest extends PostgresIntegrationTest {

    @Inject
    private DSLContext dslContext;

    @Inject
    private PublicationLinkRepository publicationLinkRepository;

    @BeforeEach
    void clearDatabase() {
        dslContext.truncate(CHANNELS).restartIdentity().cascade().execute();
        dslContext.truncate(DESTINATIONS).restartIdentity().cascade().execute();
    }

    @Test
    void findsPublicAndPrivateLinksToEveryDeliveredDestinationInOneBatch() {
        long channelId = channel("-1000000123456", "News");
        long messageId = message(channelId, 42, "https://t.me/News/42");
        long alice = destination("alice_0");
        long bob = destination("bob_0");
        publication(channelId, alice, "alice-post", messageId);
        publication(channelId, bob, "bob-post", messageId);
        var publicLink = new TelegramMessageUrl("news", 42);
        var privateLink = new TelegramMessageUrl("c/123456", 42);
        var missing = new TelegramMessageUrl("unknown", 42);

        var result = publicationLinkRepository.findTargets(Set.of(publicLink, privateLink, missing));

        List<PostingTarget> expected = List.of(
            new PostingTarget(messageId, alice, "alice_0", "alice-post"),
            new PostingTarget(messageId, bob, "bob_0", "bob-post")
        );
        assertThat(result.get(publicLink)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(result.get(privateLink)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(result).doesNotContainKey(missing);
    }

    @Test
    void preservesLookupBySavedLinkAfterChannelUsernameChanges() {
        long channelId = channel("-1000000123456", "renamed");
        long messageId = message(channelId, 42, "https://t.me/OldName/42");
        long destinationId = destination("alice_0");
        publication(channelId, destinationId, "post", messageId);

        var result = publicationLinkRepository.findTargets(Set.of(new TelegramMessageUrl("oldname", 42)));

        assertThat(result.get(new TelegramMessageUrl("oldname", 42)))
            .containsExactly(new PostingTarget(messageId, destinationId, "alice_0", "post"));
    }

    @Test
    void resolvesMessageIdentityWithoutSavedLinksAndDoesNotMixChannels() {
        long channelId = channel("-1000000123456", "news");
        long messageId = message(channelId, 42, null);
        long destinationId = destination("alice_0");
        publication(channelId, destinationId, "correct-post", messageId);
        long otherChannelId = channel("-1000000654321", "other");
        long otherMessageId = message(otherChannelId, 42, null);
        publication(otherChannelId, destinationId, "wrong-post", otherMessageId);

        var result = publicationLinkRepository.findTargets(Set.of(new TelegramMessageUrl("c/123456", 42)));

        assertThat(result.get(new TelegramMessageUrl("c/123456", 42)))
            .containsExactly(new PostingTarget(messageId, destinationId, "alice_0", "correct-post"));
    }

    @Test
    void mapsAllGroupedMessagesToTheSamePosting() {
        long channelId = channel("-1000000123456", "news");
        long first = message(channelId, 42, "https://t.me/news/42");
        long second = message(channelId, 43, "https://t.me/news/43");
        long destinationId = destination("alice_0");
        long publicationId = publication(channelId, destinationId, "group-post", first);
        addMember(publicationId, second, 1);
        var firstLink = new TelegramMessageUrl("news", 42);
        var secondLink = new TelegramMessageUrl("news", 43);

        var result = publicationLinkRepository.findTargets(Set.of(firstLink, secondLink));

        assertThat(result.get(firstLink)).containsExactly(new PostingTarget(first, destinationId, "alice_0", "group-post"));
        assertThat(result.get(secondLink))
            .containsExactly(new PostingTarget(second, destinationId, "alice_0", "group-post"));
    }

    @Test
    void excludesPublicationsWithoutDestinationPostEvenIfPreviouslyDelivered() {
        long channelId = channel("-1000000123456", "news");
        long first = message(channelId, 42, "https://t.me/news/42");
        long destinationId = destination("alice_0");
        long publicationId = publication(channelId, destinationId, null, first);
        dslContext.update(PUBLICATIONS).set(PUBLICATIONS.PUBLISHED_REVISION, 0)
            .where(PUBLICATIONS.ID.eq(publicationId)).execute();

        assertThat(publicationLinkRepository.findTargets(Set.of(new TelegramMessageUrl("news", 42)))).isEmpty();
        assertThat(publicationLinkRepository.findTargets(Set.of())).isEmpty();
    }

    private long channel(String sourceId, String username) {
        return dslContext.insertInto(CHANNELS)
            .set(CHANNELS.SOURCE_TYPE, TELEGRAM)
            .set(CHANNELS.SOURCE_ID, sourceId)
            .set(CHANNELS.SOURCE_URI, "https://t.me/" + username)
            .set(CHANNELS.PUBLIC_USERNAME, username)
            .set(CHANNELS.ACCESS_STATE, GRANTED)
            .set(CHANNELS.SUBSCRIPTION_STATE, ACTIVE)
            .returning(CHANNELS.ID).fetchSingle().getId();
    }

    private long message(long channelId, long publicId, String uri) {
        return dslContext.insertInto(MESSAGES)
            .set(MESSAGES.CHANNEL_ID, channelId)
            .set(MESSAGES.SOURCE_ID, Long.toString(publicId << 20))
            .set(MESSAGES.PUBLISHED_AT, OffsetDateTime.now())
            .set(MESSAGES.SOURCE_BODY_HASH, new byte[32])
            .set(MESSAGES.EXTERNAL_SOURCE_URI, uri)
            .returning(MESSAGES.ID).fetchSingle().getId();
    }

    private long destination(String name) {
        return dslContext.insertInto(DESTINATIONS).set(DESTINATIONS.NODE_NAME, name)
            .returning(DESTINATIONS.ID).fetchSingle().getId();
    }

    private long publication(long channelId, long destinationId, String postId, long messageId) {
        long mirrorId = dslContext.insertInto(MIRRORS)
            .set(MIRRORS.CHANNEL_ID, channelId)
            .set(MIRRORS.DESTINATION_ID, destinationId)
            .set(MIRRORS.FEEDS, new String[] { "timeline" })
            .returning(MIRRORS.ID).fetchSingle().getId();
        long publicationId = dslContext.insertInto(PUBLICATIONS)
            .set(PUBLICATIONS.MIRROR_ID, mirrorId)
            .set(PUBLICATIONS.DESTINATION_POST_ID, postId)
            .returning(PUBLICATIONS.ID).fetchSingle().getId();
        addMember(publicationId, messageId, 0);
        return publicationId;
    }

    private void addMember(long publicationId, long messageId, int position) {
        dslContext.insertInto(PUBLICATION_MESSAGES)
            .set(PUBLICATION_MESSAGES.PUBLICATION_ID, publicationId)
            .set(PUBLICATION_MESSAGES.MESSAGE_ID, messageId)
            .set(PUBLICATION_MESSAGES.POSITION, position)
            .execute();
    }

}
