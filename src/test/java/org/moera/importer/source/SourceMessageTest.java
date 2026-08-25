package org.moera.importer.source;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.moera.importer.model.BodyFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceMessageTest {

    @Test
    void protectsHashAndMediaFromMutation() {
        byte[] sourceBodyHash = new byte[32];
        List<SourceMedia> media = new ArrayList<>();
        SourceMessage message = new SourceMessage(
            "message-1",
            false,
            Instant.parse("2026-08-26T00:00:00Z"),
            "body",
            BodyFormat.PLAIN_TEXT,
            sourceBodyHash,
            JsonNodeFactory.instance.objectNode(),
            media
        );

        sourceBodyHash[0] = 1;
        media.add(null);
        byte[] returnedHash = message.sourceBodyHash();
        returnedHash[1] = 1;

        assertThat(message.sourceBodyHash()).containsOnly(0);
        assertThat(message.media()).isEmpty();
        assertThatThrownBy(() -> message.media().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

}
