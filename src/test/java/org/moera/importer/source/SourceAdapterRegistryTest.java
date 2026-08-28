package org.moera.importer.source;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.moera.importer.model.SourceType;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceAdapterRegistryTest {

    @Test
    void returnsRegisteredAdapter() {
        SourceAdapter adapter = adapter(SourceType.TELEGRAM);
        SourceAdapterRegistry registry = registry(adapter);

        assertThat(registry.get(SourceType.TELEGRAM)).isSameAs(adapter);
    }

    @Test
    void rejectsDuplicateSourceTypes() {
        SourceAdapter first = adapter(SourceType.TELEGRAM);
        SourceAdapter second = adapter(SourceType.TELEGRAM);

        assertThatThrownBy(() -> registry(first, second))
            .isInstanceOf(BeanCreationException.class)
            .rootCause()
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TELEGRAM");
    }

    @Test
    void rejectsUnregisteredSourceType() {
        SourceAdapterRegistry registry = registry();

        assertThatThrownBy(() -> registry.get(SourceType.TELEGRAM))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TELEGRAM");
    }

    private SourceAdapter adapter(SourceType sourceType) {
        return new TestSourceAdapter(sourceType);
    }

    private SourceAdapterRegistry registry(SourceAdapter... sourceAdapters) {
        try (var context = new AnnotationConfigApplicationContext()) {
            for (int index = 0; index < sourceAdapters.length; index++) {
                context.getBeanFactory().registerSingleton("sourceAdapter" + index, sourceAdapters[index]);
            }
            context.register(SourceAdapterRegistry.class);
            context.refresh();
            return context.getBean(SourceAdapterRegistry.class);
        }
    }

    private record TestSourceAdapter(SourceType type) implements SourceAdapter {

        @Override
        public ChannelResolution resolveChannel(URI sourceUri, InitialImportMode initialImportMode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Duration groupingThreshold() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Duration settlingDelay() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Duration pollingInterval(ChannelSnapshot channel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GroupingRelation groupingRelation(SourceMessage previous, SourceMessage next) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccessResult refreshAccess(ChannelSnapshot channel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SubscriptionResult refreshSubscription(ChannelSnapshot channel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PollResult poll(ChannelSnapshot channel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void downloadMedia(ChannelSnapshot channel, MediaSnapshot media, Path destination) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void start(SourceEventSink sink) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void stop() {
            throw new UnsupportedOperationException();
        }

    }

}
