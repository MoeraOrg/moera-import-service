package org.moera.importer.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.stream.Stream;
import jakarta.inject.Inject;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.stereotype.Repository;

import static org.jooq.impl.DSL.min;
import static org.moera.importer.persistence.jooq.enums.PublicationMediaStatus.PENDING;
import static org.moera.importer.persistence.jooq.tables.Channels.CHANNELS;
import static org.moera.importer.persistence.jooq.tables.PublicationMedia.PUBLICATION_MEDIA;
import static org.moera.importer.persistence.jooq.tables.Publications.PUBLICATIONS;

@Repository
public class BacklogRepository {

    @Inject
    private Clock clock;

    @Inject
    private DSLContext dslContext;

    public double channelOverdueAgeSeconds() {
        var record = dslContext.select(
                min(CHANNELS.ACCESS_RETRY_AT),
                min(CHANNELS.SUBSCRIPTION_RETRY_AT),
                min(CHANNELS.NEXT_POLL_AT)
            )
            .from(CHANNELS)
            .fetchSingle();
        OffsetDateTime oldest = Stream.of(
                record.value1(), record.value2(), record.value3()
            )
            .filter(Objects::nonNull)
            .min(OffsetDateTime::compareTo)
            .orElse(null);
        return overdueAge(oldest);
    }

    public double publicationMediaOverdueAgeSeconds() {
        Field<OffsetDateTime> oldestField = min(PUBLICATION_MEDIA.NEXT_ATTEMPT_AT);
        OffsetDateTime oldest = dslContext.select(oldestField)
            .from(PUBLICATION_MEDIA)
            .where(PUBLICATION_MEDIA.STATUS.eq(PENDING))
            .fetchSingle(oldestField);
        return overdueAge(oldest);
    }

    public double publicationOverdueAgeSeconds() {
        Field<OffsetDateTime> oldestField = min(PUBLICATIONS.NEXT_ATTEMPT_AT);
        OffsetDateTime oldest = dslContext.select(oldestField)
            .from(PUBLICATIONS)
            .where(PUBLICATIONS.REVISION.isDistinctFrom(PUBLICATIONS.PUBLISHED_REVISION))
            .fetchSingle(oldestField);
        return overdueAge(oldest);
    }

    private double overdueAge(OffsetDateTime oldest) {
        if (oldest == null) {
            return 0;
        }
        return Math.max(0, Duration.between(oldest.toInstant(), clock.instant()).toSeconds());
    }

}
