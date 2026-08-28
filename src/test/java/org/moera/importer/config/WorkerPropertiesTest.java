package org.moera.importer.config;

import java.time.Duration;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerPropertiesTest {

    @Test
    void usesIndependentPositiveWorkflowDefaults() {
        WorkerProperties properties = new WorkerProperties();

        assertThat(properties.getChannelConcurrency()).isEqualTo(2);
        assertThat(properties.getPublicationBuilderConcurrency()).isEqualTo(2);
        assertThat(properties.getPublicationMediaConcurrency()).isEqualTo(4);
        assertThat(properties.getPublicationConcurrency()).isEqualTo(4);
    }

    @Test
    void rejectsNonPositiveCapacityOrLease() {
        assertInvalid(properties -> properties.setBatchSize(0));
        assertInvalid(properties -> properties.setChannelConcurrency(0));
        assertInvalid(properties -> properties.setPublicationBuilderConcurrency(0));
        assertInvalid(properties -> properties.setPublicationMediaConcurrency(0));
        assertInvalid(properties -> properties.setPublicationConcurrency(0));
        assertInvalid(properties -> properties.setChannelLease(Duration.ZERO));
        assertInvalid(properties -> properties.setPublicationMediaLease(Duration.ZERO));
        assertInvalid(properties -> properties.setPublicationLease(Duration.ZERO));
    }

    private void assertInvalid(Consumer<WorkerProperties> invalidation) {
        WorkerProperties properties = new WorkerProperties();
        invalidation.accept(properties);

        assertThatThrownBy(properties::validate)
            .isInstanceOf(IllegalStateException.class);
    }

}
