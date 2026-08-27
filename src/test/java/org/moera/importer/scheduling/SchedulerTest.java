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
import org.moera.importer.config.WorkerProperties;
import org.moera.importer.model.AccessState;
import org.moera.importer.model.ChannelOperation;
import org.moera.importer.model.SourceType;
import org.moera.importer.model.SubscriptionState;
import org.moera.importer.persistence.ChannelClaim;
import org.moera.importer.persistence.ChannelRepository;
import org.moera.importer.persistence.MirrorRepository;
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
