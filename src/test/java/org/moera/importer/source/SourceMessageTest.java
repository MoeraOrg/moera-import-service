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
    void protectsHashMediaAndLinkPreviewsFromMutation() {
        byte[] sourceBodyHash = new byte[32];
        List<SourceMedia> media = new ArrayList<>();
        List<SourceLinkPreview> linkPreviews = new ArrayList<>();
        SourceMessage message = new SourceMessage(
            "message-1",
            false,
            true,
            Instant.parse("2026-08-26T00:00:00Z"),
            "body",
            BodyFormat.PLAIN_TEXT,
            sourceBodyHash,
            JsonNodeFactory.instance.objectNode(),
            media,
            linkPreviews
        );

        sourceBodyHash[0] = 1;
        media.add(null);
        linkPreviews.add(null);
        byte[] returnedHash = message.sourceBodyHash();
        returnedHash[1] = 1;

        assertThat(message.sourceBodyHash()).containsOnly(0);
        assertThat(message.pinned()).isTrue();
        assertThat(message.media()).isEmpty();
        assertThat(message.linkPreviews()).isEmpty();
        assertThatThrownBy(() -> message.media().add(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> message.linkPreviews().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

}
