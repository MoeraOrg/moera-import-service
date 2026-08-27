package org.moera.importer.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moera.importer.model.BodyFormat;
import org.moera.importer.model.Destination;
import org.moera.importer.model.Message;
import org.moera.importer.moera.CreatedPosting;
import org.moera.importer.moera.DestinationCapabilities;
import org.moera.importer.moera.MoeraOutcomeUnknownException;
import org.moera.importer.moera.MoeraPermanentException;
import org.moera.importer.moera.MoeraService;
import org.moera.importer.persistence.PublicationClaim;
import org.moera.importer.persistence.PublicationContext;
import org.moera.importer.persistence.PublicationRepository;
import org.moera.importer.persistence.PublicationSnapshotChangedException;
import org.moera.lib.node.types.SourceFormat;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class PublicationProcessorTest {

    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");

    private PublicationProcessor publicationProcessor;
    private RecordingPublicationRepository publicationRepository;
    private FakeMoeraService moeraService;

    @BeforeEach
    void configureProcessor() {
        publicationProcessor = new PublicationProcessor();
        publicationRepository = new RecordingPublicationRepository();
        moeraService = new FakeMoeraService();
        ReflectionTestUtils.setField(publicationProcessor, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(publicationProcessor, "moeraService", moeraService);
        ReflectionTestUtils.setField(
            publicationProcessor, "publicationPayloadBuilder", new PublicationPayloadBuilder()
        );
        ReflectionTestUtils.setField(
            publicationProcessor, "publicationRepository", publicationRepository
        );
        ReflectionTestUtils.setField(
            publicationProcessor,
            "retryPolicy",
            new RetryPolicy(Duration.ofMinutes(1), Duration.ofHours(1), 0, () -> 0.5)
        );
    }

    @Test
    void createsNonEmptyPublicationAndPersistsPostingStoriesAndPin() {
        publicationRepository.context = context(null, objectNode(), "Body", true, false);
        moeraService.createdPosting = new CreatedPosting(
            "posting-1", linkedMap("timeline", "story-1", "news", "story-2")
        );

        publicationProcessor.process(claim());

        assertThat(moeraService.operations).containsExactly("capabilities", "create");
        assertThat(publicationRepository.completion).isEqualTo("created");
        assertThat(publicationRepository.postingId).isEqualTo("posting-1");
        assertThat(publicationRepository.destinationData.path("pinned").asBoolean()).isTrue();
        assertThat(publicationRepository.destinationData.path("storyIds").path("timeline").asText())
            .isEqualTo("story-1");
        assertThat(publicationRepository.destinationData.path("storyIds").path("news").asText())
            .isEqualTo("story-2");
    }

    @Test
    void updatesExistingNonEmptyPublicationWithoutChangingFeedMembership() {
        publicationRepository.context = context(
            "posting-1", destinationData(false, linkedMap("timeline", "story-1")), "Updated", false, false
        );

        publicationProcessor.process(claim());

        assertThat(moeraService.operations).containsExactly("capabilities", "update:posting-1");
        assertThat(moeraService.lastPayload.publications()).isNotEmpty();
        assertThat(publicationRepository.completion).isEqualTo("updated");
        assertThat(publicationRepository.destinationData.path("storyIds").path("timeline").asText())
            .isEqualTo("story-1");
    }

    @Test
    void deletesExistingPostWhenAllContentDisappears() {
        publicationRepository.context = context(
            "posting-1", destinationData(true, linkedMap("timeline", "story-1")), "", false, true
        );

        publicationProcessor.process(claim());

        assertThat(moeraService.operations).containsExactly("capabilities", "delete:posting-1");
        assertThat(publicationRepository.completion).isEqualTo("deleted");
        assertThat(publicationRepository.destinationData).isEqualTo(objectNode());
    }

    @Test
    void completesEmptyNeverCreatedPublicationWithoutExternalMutation() {
        publicationRepository.context = context(null, objectNode(), "", false, true);

        publicationProcessor.process(claim());

        assertThat(moeraService.operations).containsExactly("capabilities");
        assertThat(publicationRepository.completion).isEqualTo("deleted");
        assertThat(publicationRepository.destinationData).isEqualTo(objectNode());
    }

    @Test
    void retriesUnknownCreateOutcomeWithoutCompletingRevision() {
        publicationRepository.context = context(null, objectNode(), "Body", false, false);
        moeraService.createFailure = new MoeraOutcomeUnknownException("create outcome unknown", null);

        publicationProcessor.process(claim());

        assertThat(publicationRepository.completion).isNull();
        assertThat(publicationRepository.nextAttemptAt).isEqualTo(NOW.plus(Duration.ofMinutes(4)));
        assertThat(publicationRepository.error).isEqualTo("create outcome unknown");
    }

    @Test
    void pinsEveryStoredStoryAfterUpdatingPost() {
        publicationRepository.context = context(
            "posting-1",
            destinationData(false, linkedMap("timeline", "story-1", "news", "story-2")),
            "Body",
            true,
            false
        );

        publicationProcessor.process(claim());

        assertThat(moeraService.operations).containsExactly(
            "capabilities", "update:posting-1", "story:story-1:true", "story:story-2:true"
        );
        assertThat(publicationRepository.completion).isEqualTo("updated");
        assertThat(publicationRepository.destinationData.path("pinned").asBoolean()).isTrue();
    }

    @Test
    void unpinsEveryStoredStory() {
        publicationRepository.context = context(
            "posting-1",
            destinationData(true, linkedMap("timeline", "story-1", "news", "story-2")),
            "Body",
            false,
            false
        );

        publicationProcessor.process(claim());

        assertThat(moeraService.operations).containsExactly(
            "capabilities", "update:posting-1", "story:story-1:false", "story:story-2:false"
        );
        assertThat(publicationRepository.destinationData.path("pinned").asBoolean()).isFalse();
    }

    @Test
    void partialStoryUpdateFailureRetriesWithoutCompletingRevision() {
        publicationRepository.context = context(
            "posting-1",
            destinationData(false, linkedMap("timeline", "story-1", "news", "story-2")),
            "Body",
            true,
            false
        );
        moeraService.failingStoryId = "story-2";
        moeraService.storyFailure = new MoeraOutcomeUnknownException("story outcome unknown", null);

        publicationProcessor.process(claim());

        assertThat(moeraService.operations).containsExactly(
            "capabilities", "update:posting-1", "story:story-1:true", "story:story-2:true"
        );
        assertThat(publicationRepository.completion).isNull();
        assertThat(publicationRepository.nextAttemptAt).isEqualTo(NOW.plus(Duration.ofMinutes(4)));
        assertThat(publicationRepository.error).isEqualTo("story outcome unknown");
    }

    @Test
    void permanentFailureStopsAutomaticScheduling() {
        publicationRepository.context = context("posting-1", objectNode(), "Body", false, false);
        moeraService.updateFailure = new MoeraPermanentException("invalid posting", null);

        publicationProcessor.process(claim());

        assertThat(publicationRepository.completion).isNull();
        assertThat(publicationRepository.failed).isTrue();
        assertThat(publicationRepository.error).isEqualTo("invalid posting");
    }

    @Test
    void releasesClaimImmediatelyWhenRevisionChangedBeforeSnapshot() {
        publicationRepository.snapshotChanged = true;

        publicationProcessor.process(claim());

        assertThat(moeraService.operations).isEmpty();
        assertThat(publicationRepository.completion).isNull();
        assertThat(publicationRepository.nextAttemptAt).isEqualTo(NOW);
        assertThat(publicationRepository.error).isNull();
    }

    private PublicationClaim claim() {
        return new PublicationClaim(17, 5, UUID.randomUUID(), 3);
    }

    private PublicationContext context(
        String destinationPostId,
        JsonNode destinationData,
        String body,
        boolean pinned,
        boolean deleted
    ) {
        ObjectNode empty = objectNode();
        Destination destination = new Destination(7, "destination.example", empty, empty, NOW, NOW);
        Message message = new Message(
            11,
            5,
            "message-1",
            0,
            deleted,
            pinned,
            NOW.minusSeconds(60),
            body,
            BodyFormat.HTML,
            hash(1),
            empty,
            NOW,
            NOW
        );
        ObjectNode mirrorConfig = objectNode();
        mirrorConfig.putArray("feeds").add("timeline").add("news");
        return new PublicationContext(
            17,
            5,
            destination,
            mirrorConfig,
            destinationPostId,
            destinationData,
            List.of(new PublicationContext.Member(message, List.of())),
            List.of()
        );
    }

    private ObjectNode destinationData(boolean pinned, Map<String, String> storyIds) {
        ObjectNode data = objectNode().put("pinned", pinned);
        ObjectNode stories = data.putObject("storyIds");
        storyIds.forEach(stories::put);
        return data;
    }

    private Map<String, String> linkedMap(String... keysAndValues) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            result.put(keysAndValues[index], keysAndValues[index + 1]);
        }
        return result;
    }

    private ObjectNode objectNode() {
        return JsonNodeFactory.instance.objectNode();
    }

    private byte[] hash(int firstByte) {
        byte[] hash = new byte[32];
        hash[0] = (byte) firstByte;
        return hash;
    }

    private static class RecordingPublicationRepository extends PublicationRepository {

        private PublicationContext context;
        private String completion;
        private String postingId;
        private JsonNode destinationData;
        private Instant nextAttemptAt;
        private String error;
        private boolean failed;
        private boolean snapshotChanged;

        @Override
        public PublicationContext loadContext(long publicationId, int targetRevision) {
            if (snapshotChanged) {
                throw new PublicationSnapshotChangedException();
            }
            return context;
        }

        @Override
        public boolean created(
            long publicationId,
            UUID token,
            int targetRevision,
            String postingId,
            JsonNode destinationData
        ) {
            completion = "created";
            this.postingId = postingId;
            this.destinationData = destinationData;
            return true;
        }

        @Override
        public boolean updated(
            long publicationId,
            UUID token,
            int targetRevision,
            JsonNode destinationData
        ) {
            completion = "updated";
            this.destinationData = destinationData;
            return true;
        }

        @Override
        public boolean deleted(
            long publicationId,
            UUID token,
            int targetRevision,
            JsonNode destinationData
        ) {
            completion = "deleted";
            this.destinationData = destinationData;
            return true;
        }

        @Override
        public boolean retry(
            long publicationId,
            UUID token,
            Instant nextAttemptAt,
            String error
        ) {
            this.nextAttemptAt = nextAttemptAt;
            this.error = error;
            return true;
        }

        @Override
        public boolean fail(long publicationId, UUID token, String error) {
            failed = true;
            this.error = error;
            return true;
        }

    }

    private static class FakeMoeraService extends MoeraService {

        private final List<String> operations = new ArrayList<>();
        private DestinationCapabilities capabilities = new DestinationCapabilities(
            1000, List.of(SourceFormat.HTML)
        );
        private CreatedPosting createdPosting = new CreatedPosting("posting-1", Map.of());
        private RuntimeException createFailure;
        private RuntimeException updateFailure;
        private RuntimeException storyFailure;
        private String failingStoryId;
        private PublicationPayload lastPayload;

        @Override
        public DestinationCapabilities capabilities(Destination destination) {
            operations.add("capabilities");
            return capabilities;
        }

        @Override
        public CreatedPosting createPost(Destination destination, PublicationPayload payload) {
            operations.add("create");
            lastPayload = payload;
            throwIfPresent(createFailure);
            return createdPosting;
        }

        @Override
        public void updatePost(Destination destination, String postingId, PublicationPayload payload) {
            operations.add("update:" + postingId);
            lastPayload = payload;
            throwIfPresent(updateFailure);
        }

        @Override
        public void deletePost(Destination destination, String postingId) {
            operations.add("delete:" + postingId);
        }

        @Override
        public void updateStory(Destination destination, String storyId, boolean pinned) {
            operations.add("story:" + storyId + ":" + pinned);
            if (storyId.equals(failingStoryId)) {
                throwIfPresent(storyFailure);
            }
        }

        private void throwIfPresent(RuntimeException failure) {
            if (failure != null) {
                throw failure;
            }
        }

    }

}
