package org.moera.importer.scheduling;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.moera.importer.application.ChannelProcessor;
import org.moera.importer.application.PublicationBuilder;
import org.moera.importer.application.PublicationMediaProcessor;
import org.moera.importer.application.PublicationProcessor;
import org.moera.importer.config.WorkerProperties;
import org.moera.importer.model.AccessState;
import org.moera.importer.model.ChannelOperation;
import org.moera.importer.model.SourceType;
import org.moera.importer.model.SubscriptionState;
import org.moera.importer.persistence.ChannelClaim;
import org.moera.importer.persistence.ChannelRepository;
import org.moera.importer.persistence.MirrorRepository;
import org.moera.importer.persistence.PublicationMediaClaim;
import org.moera.importer.persistence.PublicationMediaRepository;
import org.moera.importer.persistence.PublicationClaim;
import org.moera.importer.persistence.PublicationRepository;
import org.moera.importer.source.ChannelSnapshot;
import org.moera.importer.source.telegram.TelegramRuntime;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchedulerTest {

    @Test
    void channelSchedulerClaimsInPriorityOrderUpToBatchSize() {
        RecordingChannelRepository channelRepository = new RecordingChannelRepository();
        RecordingChannelProcessor channelProcessor = new RecordingChannelProcessor();
        WorkerProperties workerProperties = properties();
        ChannelScheduler channelScheduler = new ChannelScheduler();
        TelegramRuntime telegramRuntime = mock(TelegramRuntime.class);
        when(telegramRuntime.channelWorkReady()).thenReturn(true);
        ReflectionTestUtils.setField(channelScheduler, "channelProcessor", channelProcessor);
        ReflectionTestUtils.setField(channelScheduler, "channelRepository", channelRepository);
        ReflectionTestUtils.setField(channelScheduler, "executorService", new DirectExecutorService());
        ReflectionTestUtils.setField(channelScheduler, "workerProperties", workerProperties);
        ReflectionTestUtils.setField(channelScheduler, "telegramRuntime", telegramRuntime);

        channelScheduler.run();

        assertThat(channelRepository.requestedOperations).containsExactly(
            ChannelOperation.ACCESS,
            ChannelOperation.SUBSCRIPTION,
            ChannelOperation.POLL
        );
        assertThat(channelRepository.requestedLimits).containsExactly(3, 2, 1);
        assertThat(channelProcessor.processedOperations).containsExactly(
            ChannelOperation.ACCESS,
            ChannelOperation.SUBSCRIPTION,
            ChannelOperation.POLL
        );
    }

    @Test
    void channelSchedulerDoesNotClaimWorkUntilTelegramIsReady() {
        RecordingChannelRepository channelRepository = new RecordingChannelRepository();
        ChannelScheduler channelScheduler = new ChannelScheduler();
        TelegramRuntime telegramRuntime = mock(TelegramRuntime.class);
        when(telegramRuntime.channelWorkReady()).thenReturn(false);
        ReflectionTestUtils.setField(channelScheduler, "channelRepository", channelRepository);
        ReflectionTestUtils.setField(channelScheduler, "workerProperties", properties());
        ReflectionTestUtils.setField(channelScheduler, "telegramRuntime", telegramRuntime);

        channelScheduler.run();

        assertThat(channelRepository.requestedOperations).isEmpty();
    }

    @Test
    void publicationBuilderSchedulerDispatchesFoundMirrors() {
        RecordingMirrorRepository mirrorRepository = new RecordingMirrorRepository();
        RecordingPublicationBuilder publicationBuilder = new RecordingPublicationBuilder();
        PublicationBuilderScheduler scheduler = new PublicationBuilderScheduler();
        ReflectionTestUtils.setField(scheduler, "executorService", new DirectExecutorService());
        ReflectionTestUtils.setField(scheduler, "mirrorRepository", mirrorRepository);
        ReflectionTestUtils.setField(scheduler, "publicationBuilder", publicationBuilder);
        ReflectionTestUtils.setField(scheduler, "workerProperties", properties());

        scheduler.run();

        assertThat(mirrorRepository.requestedLimit).isEqualTo(3);
        assertThat(publicationBuilder.mirrorIds).containsExactly(7L, 9L);
    }

    @Test
    void publicationMediaSchedulerClaimsConfiguredBatchAndDispatchesIt() {
        RecordingPublicationMediaRepository publicationMediaRepository =
            new RecordingPublicationMediaRepository();
        RecordingPublicationMediaProcessor publicationMediaProcessor =
            new RecordingPublicationMediaProcessor();
        WorkerProperties workerProperties = properties();
        workerProperties.setPublicationMediaLease(Duration.ofMinutes(7));
        PublicationMediaScheduler scheduler = new PublicationMediaScheduler();
        ReflectionTestUtils.setField(scheduler, "executorService", new DirectExecutorService());
        ReflectionTestUtils.setField(scheduler, "publicationMediaProcessor", publicationMediaProcessor);
        ReflectionTestUtils.setField(scheduler, "publicationMediaRepository", publicationMediaRepository);
        ReflectionTestUtils.setField(scheduler, "workerProperties", workerProperties);

        scheduler.run();

        assertThat(publicationMediaRepository.requestedLimit).isEqualTo(3);
        assertThat(publicationMediaRepository.requestedLease).isEqualTo(Duration.ofMinutes(7));
        assertThat(publicationMediaProcessor.mediaIds).containsExactly(23L, 29L);
    }

    @Test
    void publicationSchedulerClaimsConfiguredBatchAndDispatchesIt() {
        RecordingPublicationRepository publicationRepository = new RecordingPublicationRepository();
        RecordingPublicationProcessor publicationProcessor = new RecordingPublicationProcessor();
        WorkerProperties workerProperties = properties();
        workerProperties.setPublicationLease(Duration.ofMinutes(9));
        PublicationScheduler scheduler = new PublicationScheduler();
        ReflectionTestUtils.setField(scheduler, "executorService", new DirectExecutorService());
        ReflectionTestUtils.setField(scheduler, "publicationProcessor", publicationProcessor);
        ReflectionTestUtils.setField(scheduler, "publicationRepository", publicationRepository);
        ReflectionTestUtils.setField(scheduler, "workerProperties", workerProperties);

        scheduler.run();

        assertThat(publicationRepository.requestedLimit).isEqualTo(3);
        assertThat(publicationRepository.requestedLease).isEqualTo(Duration.ofMinutes(9));
        assertThat(publicationProcessor.publicationIds).containsExactly(31L, 37L);
    }

    private WorkerProperties properties() {
        WorkerProperties properties = new WorkerProperties();
        properties.setBatchSize(3);
        properties.setChannelLease(Duration.ofMinutes(5));
        return properties;
    }

    private static class RecordingChannelRepository extends ChannelRepository {

        private final List<ChannelOperation> requestedOperations = new ArrayList<>();
        private final List<Integer> requestedLimits = new ArrayList<>();

        @Override
        public List<ChannelClaim> claimAccess(int limit, Duration lease) {
            return claim(ChannelOperation.ACCESS, limit);
        }

        @Override
        public List<ChannelClaim> claimSubscriptions(int limit, Duration lease) {
            return claim(ChannelOperation.SUBSCRIPTION, limit);
        }

        @Override
        public List<ChannelClaim> claimPolls(int limit, Duration lease) {
            return claim(ChannelOperation.POLL, limit);
        }

        private List<ChannelClaim> claim(ChannelOperation operation, int limit) {
            requestedOperations.add(operation);
            requestedLimits.add(limit);
            return List.of(new ChannelClaim(channel(), operation, UUID.randomUUID(), 1));
        }

        private ChannelSnapshot channel() {
            var empty = JsonNodeFactory.instance.objectNode();
            return new ChannelSnapshot(
                17,
                SourceType.TELEGRAM,
                "channel-1",
                URI.create("https://t.me/channel-1"),
                empty,
                AccessState.GRANTED,
                empty,
                SubscriptionState.ACTIVE,
                empty,
                empty
            );
        }

    }

    private static class RecordingChannelProcessor extends ChannelProcessor {

        private final List<ChannelOperation> processedOperations = new ArrayList<>();

        @Override
        public void process(ChannelClaim claim) {
            processedOperations.add(claim.operation());
        }

    }

    private static class RecordingMirrorRepository extends MirrorRepository {

        private int requestedLimit;

        @Override
        public List<Long> findWithUnassignedMessages(int limit) {
            requestedLimit = limit;
            return List.of(7L, 9L);
        }

    }

    private static class RecordingPublicationBuilder extends PublicationBuilder {

        private final List<Long> mirrorIds = new ArrayList<>();

        @Override
        public void build(long mirrorId) {
            mirrorIds.add(mirrorId);
        }

    }

    private static class RecordingPublicationMediaRepository extends PublicationMediaRepository {

        private int requestedLimit;
        private Duration requestedLease;

        @Override
        public List<PublicationMediaClaim> claimPending(int limit, Duration lease) {
            requestedLimit = limit;
            requestedLease = lease;
            return List.of(
                new PublicationMediaClaim(17, 23, UUID.randomUUID(), 1),
                new PublicationMediaClaim(19, 29, UUID.randomUUID(), 1)
            );
        }

    }

    private static class RecordingPublicationMediaProcessor extends PublicationMediaProcessor {

        private final List<Long> mediaIds = new ArrayList<>();

        @Override
        public void process(PublicationMediaClaim claim) {
            mediaIds.add(claim.mediaId());
        }

    }

    private static class RecordingPublicationRepository extends PublicationRepository {

        private int requestedLimit;
        private Duration requestedLease;

        @Override
        public List<PublicationClaim> claimReady(int limit, Duration lease) {
            requestedLimit = limit;
            requestedLease = lease;
            return List.of(
                new PublicationClaim(31, 4, UUID.randomUUID(), 1),
                new PublicationClaim(37, 2, UUID.randomUUID(), 1)
            );
        }

    }

    private static class RecordingPublicationProcessor extends PublicationProcessor {

        private final List<Long> publicationIds = new ArrayList<>();

        @Override
        public void process(PublicationClaim claim) {
            publicationIds.add(claim.publicationId());
        }

    }

    private static class DirectExecutorService extends AbstractExecutorService {

        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }

    }

}
