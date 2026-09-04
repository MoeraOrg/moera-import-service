package org.moera.importer.source;

import java.net.URI;
import java.time.Instant;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.moera.importer.model.AccessState;
import org.moera.importer.model.SourceType;
import org.moera.importer.model.SubscriptionState;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChannelSnapshotTest {

    @Test
    void afterModeRequiresInitialImportBoundary() {
        assertThatThrownBy(() -> snapshot(InitialImportMode.AFTER, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void otherModesRejectInitialImportBoundary() {
        assertThatThrownBy(() -> snapshot(InitialImportMode.RECENT, Instant.parse("2026-09-01T11:30:00Z")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private ChannelSnapshot snapshot(InitialImportMode initialImportMode, Instant initialImportAfter) {
        return new ChannelSnapshot(
            1,
            SourceType.TELEGRAM,
            "-1001",
            URI.create("https://t.me/news"),
            JsonNodeFactory.instance.objectNode(),
            initialImportMode,
            initialImportAfter,
            null,
            AccessState.GRANTED,
            JsonNodeFactory.instance.objectNode(),
            SubscriptionState.ACTIVE,
            JsonNodeFactory.instance.objectNode(),
            null
        );
    }

}
