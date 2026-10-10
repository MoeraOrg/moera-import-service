package org.moera.importer.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import jakarta.inject.Inject;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.moera.importer.model.Destination;
import org.moera.importer.moera.CreatedPosting;
import org.moera.importer.moera.DestinationCapabilities;
import org.moera.importer.moera.MoeraService;
import org.moera.importer.persistence.PostgresIntegrationTest;
import org.moera.importer.persistence.PublicationClaim;
import org.moera.importer.persistence.PublicationRepository;
import org.moera.lib.node.types.SourceFormat;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.moera.importer.persistence.jooq.enums.AccessState.GRANTED;
import static org.moera.importer.persistence.jooq.enums.BodyFormat.HTML;
import static org.moera.importer.persistence.jooq.enums.SourceType.TELEGRAM;
import static org.moera.importer.persistence.jooq.enums.SubscriptionState.ACTIVE;
import static org.moera.importer.persistence.jooq.tables.Channels.CHANNELS;
import static org.moera.importer.persistence.jooq.tables.Destinations.DESTINATIONS;
import static org.moera.importer.persistence.jooq.tables.MessageLinkPreviews.MESSAGE_LINK_PREVIEWS;
import static org.moera.importer.persistence.jooq.tables.Messages.MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Mirrors.MIRRORS;
import static org.moera.importer.persistence.jooq.tables.PublicationMessages.PUBLICATION_MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Publications.PUBLICATIONS;

@SpringBootTest
@Import(PublicationLinkRewriteIntegrationTest.Configuration.class)
class PublicationLinkRewriteIntegrationTest extends PostgresIntegrationTest {

    private static final String TELEGRAM_URL = "https://t.me/news/100";
    private static final String MOERA_URL = "https://moera.page/@alice/~/post/target-post";

    @Inject
    private DSLContext dslContext;

    @Inject
    private PublicationProcessor publicationProcessor;

    @Inject
    private PublicationRepository publicationRepository;

    @Inject
    private CapturingMoeraService capturingMoeraService;

    @BeforeEach
    void clearDatabase() {
        dslContext.truncate(CHANNELS).restartIdentity().cascade().execute();
        dslContext.truncate(DESTINATIONS).restartIdentity().cascade().execute();
        capturingMoeraService.payload = null;
    }

    @Test
    void replacesLinksWhenCreatingPostWithoutChangingStoredSource() {
        long messageId = insertFixture(null);

        processReadyPublication();

        assertRewrittenPayload();
        assertThat(dslContext.select(MESSAGES.BODY).from(MESSAGES).where(MESSAGES.ID.eq(messageId))
            .fetchSingle(MESSAGES.BODY)).contains(TELEGRAM_URL);
        assertThat(capturingMoeraService.payload.externalSourceUri()).containsExactly("https://t.me/news/101");
    }

    @Test
    void replacesLinksWhenUpdatingPost() {
        insertFixture("existing-post");

        processReadyPublication();

        assertRewrittenPayload();
        assertThat(capturingMoeraService.postingId).isEqualTo("existing-post");
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void leavesPublicLinksAmbiguousBetweenReassignedUsernamesUnchanged(boolean otherDelivered) {
        insertFixture(null);
        dslContext.update(CHANNELS).set(CHANNELS.PUBLIC_USERNAME, "renamed").execute();
        long channelId = dslContext.insertInto(CHANNELS)
            .set(CHANNELS.SOURCE_TYPE, TELEGRAM)
            .set(CHANNELS.SOURCE_ID, "-1000000654321")
            .set(CHANNELS.SOURCE_URI, "https://t.me/news")
            .set(CHANNELS.PUBLIC_USERNAME, "news")
            .set(CHANNELS.ACCESS_STATE, GRANTED)
            .set(CHANNELS.SUBSCRIPTION_STATE, ACTIVE)
            .returning(CHANNELS.ID).fetchSingle().getId();
        long destinationId = dslContext.insertInto(DESTINATIONS)
            .set(DESTINATIONS.NODE_NAME, "bob_0")
            .returning(DESTINATIONS.ID).fetchSingle().getId();
        long mirrorId = dslContext.insertInto(MIRRORS)
            .set(MIRRORS.CHANNEL_ID, channelId)
            .set(MIRRORS.DESTINATION_ID, destinationId)
            .set(MIRRORS.FEEDS, new String[] { "timeline" })
            .returning(MIRRORS.ID).fetchSingle().getId();
        long messageId = insertMessage(channelId, 100, "another message", TELEGRAM_URL);
        addMember(insertPublication(mirrorId, otherDelivered ? "other-post" : null, 0), messageId);
        if (!otherDelivered) {
            dslContext.deleteFrom(PUBLICATION_MESSAGES)
                .where(PUBLICATION_MESSAGES.MESSAGE_ID.eq(messageId)).execute();
        }

        processReadyPublication();

        assertThat(capturingMoeraService.payload.bodySrc().getText()).contains(TELEGRAM_URL);
        assertThat(capturingMoeraService.payload.bodySrc().getLinkPreviews()).singleElement()
            .satisfies(preview -> assertThat(preview.getUrl()).isEqualTo(TELEGRAM_URL));
    }

    private void processReadyPublication() {
        List<PublicationClaim> claims = publicationRepository.claimReady(10, Duration.ofMinutes(5));
        assertThat(claims).hasSize(1);
        publicationProcessor.process(claims.getFirst());
    }

    private void assertRewrittenPayload() {
        assertThat(capturingMoeraService.payload.bodySrc().getText())
            .contains("href=\"" + MOERA_URL + "\"", ">" + MOERA_URL + "</a>")
            .doesNotContain("t.me");
        assertThat(capturingMoeraService.payload.bodySrc().getLinkPreviews()).singleElement()
            .satisfies(preview -> assertThat(preview.getUrl()).isEqualTo(MOERA_URL));
    }

    private long insertFixture(String postingId) {
        long channelId = dslContext.insertInto(CHANNELS)
            .set(CHANNELS.SOURCE_TYPE, TELEGRAM)
            .set(CHANNELS.SOURCE_ID, "-1000000123456")
            .set(CHANNELS.SOURCE_URI, "https://t.me/news")
            .set(CHANNELS.PUBLIC_USERNAME, "news")
            .set(CHANNELS.ACCESS_STATE, GRANTED)
            .set(CHANNELS.SUBSCRIPTION_STATE, ACTIVE)
            .returning(CHANNELS.ID).fetchSingle().getId();
        long destinationId = dslContext.insertInto(DESTINATIONS)
            .set(DESTINATIONS.NODE_NAME, "alice_0")
            .set(DESTINATIONS.PROFILE_SYNC, false)
            .returning(DESTINATIONS.ID).fetchSingle().getId();
        long mirrorId = dslContext.insertInto(MIRRORS)
            .set(MIRRORS.CHANNEL_ID, channelId)
            .set(MIRRORS.DESTINATION_ID, destinationId)
            .set(MIRRORS.FEEDS, new String[] { "timeline" })
            .returning(MIRRORS.ID).fetchSingle().getId();
        long targetId = insertMessage(channelId, 100, "target", TELEGRAM_URL);
        long targetPublicationId = insertPublication(mirrorId, "target-post", 0);
        addMember(targetPublicationId, targetId);
        long messageId = insertMessage(channelId, 101,
            "<p><a href=\"http://telegram.me/s/News/100?single\">" + TELEGRAM_URL + "</a></p>",
            "https://t.me/news/101");
        dslContext.update(MESSAGES)
            .set(MESSAGES.FORWARD_NAME, "original")
            .set(MESSAGES.FORWARD_URI, "https://t.me/c/123456/100")
            .where(MESSAGES.ID.eq(messageId)).execute();
        dslContext.insertInto(MESSAGE_LINK_PREVIEWS)
            .set(MESSAGE_LINK_PREVIEWS.MESSAGE_ID, messageId)
            .set(MESSAGE_LINK_PREVIEWS.POSITION, 0)
            .set(MESSAGE_LINK_PREVIEWS.URL, TELEGRAM_URL)
            .execute();
        long publicationId = insertPublication(mirrorId, postingId, postingId != null ? 0 : null);
        dslContext.update(PUBLICATIONS)
            .set(PUBLICATIONS.REVISION, 1)
            .set(PUBLICATIONS.NEXT_ATTEMPT_AT, OffsetDateTime.now().minusMinutes(1))
            .where(PUBLICATIONS.ID.eq(publicationId)).execute();
        addMember(publicationId, messageId);
        return messageId;
    }

    private long insertMessage(long channelId, long publicId, String body, String uri) {
        return dslContext.insertInto(MESSAGES)
            .set(MESSAGES.CHANNEL_ID, channelId)
            .set(MESSAGES.SOURCE_ID, Long.toString(publicId << 20))
            .set(MESSAGES.PUBLISHED_AT, OffsetDateTime.now().minusHours(1))
            .set(MESSAGES.BODY, body)
            .set(MESSAGES.BODY_FORMAT, HTML)
            .set(MESSAGES.SOURCE_BODY_HASH, new byte[32])
            .set(MESSAGES.EXTERNAL_SOURCE_URI, uri)
            .returning(MESSAGES.ID).fetchSingle().getId();
    }

    private long insertPublication(long mirrorId, String postingId, Integer publishedRevision) {
        return dslContext.insertInto(PUBLICATIONS)
            .set(PUBLICATIONS.MIRROR_ID, mirrorId)
            .set(PUBLICATIONS.DESTINATION_POST_ID, postingId)
            .set(PUBLICATIONS.PUBLISHED_REVISION, publishedRevision)
            .returning(PUBLICATIONS.ID).fetchSingle().getId();
    }

    private void addMember(long publicationId, long messageId) {
        dslContext.insertInto(PUBLICATION_MESSAGES)
            .set(PUBLICATION_MESSAGES.PUBLICATION_ID, publicationId)
            .set(PUBLICATION_MESSAGES.MESSAGE_ID, messageId)
            .set(PUBLICATION_MESSAGES.POSITION, 0)
            .execute();
    }

    @TestConfiguration
    static class Configuration {

        @Bean
        @Primary
        CapturingMoeraService capturingMoeraService() {
            return new CapturingMoeraService();
        }

    }

    static class CapturingMoeraService extends MoeraService {

        private PublicationPayload payload;
        private String postingId;

        @Override
        public DestinationCapabilities capabilities(Destination destination) {
            return new DestinationCapabilities(1000, List.of(SourceFormat.HTML));
        }

        @Override
        public CreatedPosting createPost(Destination destination, PublicationPayload payload) {
            this.payload = payload;
            return new CreatedPosting("created-post", Map.of());
        }

        @Override
        public void updatePost(Destination destination, String postingId, PublicationPayload payload) {
            this.payload = payload;
            this.postingId = postingId;
        }

    }

}
