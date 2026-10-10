package org.moera.importer.persistence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.inject.Inject;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.moera.importer.source.telegram.TelegramMessageUrl;
import org.springframework.stereotype.Repository;

import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.or;
import static org.moera.importer.persistence.jooq.enums.SourceType.TELEGRAM;
import static org.moera.importer.persistence.jooq.tables.Channels.CHANNELS;
import static org.moera.importer.persistence.jooq.tables.Destinations.DESTINATIONS;
import static org.moera.importer.persistence.jooq.tables.Messages.MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Mirrors.MIRRORS;
import static org.moera.importer.persistence.jooq.tables.PublicationMessages.PUBLICATION_MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Publications.PUBLICATIONS;

@Repository
public class PublicationLinkRepository {

    @Inject
    private DSLContext dslContext;

    public Map<TelegramMessageUrl, List<PostingTarget>> findTargets(Set<TelegramMessageUrl> urls) {
        if (urls.isEmpty()) {
            return Map.of();
        }
        var rows = dslContext.select(
                MESSAGES.ID, MESSAGES.EXTERNAL_SOURCE_URI, MESSAGES.SOURCE_ID, CHANNELS.SOURCE_ID,
                CHANNELS.PUBLIC_USERNAME,
                DESTINATIONS.ID, DESTINATIONS.NODE_NAME, PUBLICATIONS.DESTINATION_POST_ID
            )
            .from(MESSAGES)
            .join(CHANNELS).on(CHANNELS.ID.eq(MESSAGES.CHANNEL_ID))
            .leftJoin(PUBLICATION_MESSAGES).on(PUBLICATION_MESSAGES.MESSAGE_ID.eq(MESSAGES.ID))
            .leftJoin(PUBLICATIONS).on(PUBLICATIONS.ID.eq(PUBLICATION_MESSAGES.PUBLICATION_ID))
            .leftJoin(MIRRORS).on(MIRRORS.ID.eq(PUBLICATIONS.MIRROR_ID))
            .leftJoin(DESTINATIONS).on(DESTINATIONS.ID.eq(MIRRORS.DESTINATION_ID))
            .where(CHANNELS.SOURCE_TYPE.eq(TELEGRAM))
            .and(or(urls.stream().map(this::matches).toList()))
            .fetch();
        Map<TelegramMessageUrl, List<PostingTarget>> targets = new HashMap<>();
        Map<TelegramMessageUrl, Set<Long>> sourceMessages = new HashMap<>();
        for (var row : rows) {
            var savedUrl = TelegramMessageUrl.parse(row.get(MESSAGES.EXTERNAL_SOURCE_URI));
            PostingTarget target = row.get(PUBLICATIONS.DESTINATION_POST_ID) != null ? new PostingTarget(
                row.get(MESSAGES.ID), row.get(DESTINATIONS.ID), row.get(DESTINATIONS.NODE_NAME),
                row.get(PUBLICATIONS.DESTINATION_POST_ID)
            ) : null;
            for (TelegramMessageUrl url : urls) {
                boolean channelMatches = url.privateChannel()
                    ? url.toChannelSourceId().equals(row.get(CHANNELS.SOURCE_ID))
                    : url.channel().equalsIgnoreCase(row.get(CHANNELS.PUBLIC_USERNAME));
                if (savedUrl.filter(url::equals).isPresent()
                    || channelMatches && url.toSourceId().equals(row.get(MESSAGES.SOURCE_ID))) {
                    sourceMessages.computeIfAbsent(url, ignored -> new HashSet<>()).add(row.get(MESSAGES.ID));
                    if (target != null) {
                        targets.computeIfAbsent(url, ignored -> new ArrayList<>()).add(target);
                    }
                }
            }
        }
        targets.keySet().removeIf(url -> sourceMessages.get(url).size() != 1);
        targets.replaceAll((url, values) -> values.stream().distinct().toList());
        return Map.copyOf(targets);
    }

    private Condition matches(TelegramMessageUrl url) {
        Condition channelMatches = url.privateChannel()
            ? CHANNELS.SOURCE_ID.eq(url.toChannelSourceId())
            : lower(CHANNELS.PUBLIC_USERNAME).eq(url.channel());
        return lower(MESSAGES.EXTERNAL_SOURCE_URI).eq(url.toUri())
            .or(channelMatches.and(MESSAGES.SOURCE_ID.eq(url.toSourceId())));
    }

    public record PostingTarget(long messageId, long destinationId, String nodeName, String postingId) {
    }

}
