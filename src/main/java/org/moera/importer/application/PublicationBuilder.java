package org.moera.importer.application;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import jakarta.inject.Inject;

import org.moera.importer.model.Message;
import org.moera.importer.model.Mirror;
import org.moera.importer.persistence.ChannelRepository;
import org.moera.importer.persistence.MessageRepository;
import org.moera.importer.persistence.MirrorRepository;
import org.moera.importer.persistence.PublicationRepository;
import org.moera.importer.source.GroupingRelation;
import org.moera.importer.source.SourceAdapter;
import org.moera.importer.source.SourceAdapterRegistry;
import org.moera.importer.source.SourceMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicationBuilder {

    @Inject
    private ChannelRepository channelRepository;

    @Inject
    private Clock clock;

    @Inject
    private MessageRepository messageRepository;

    @Inject
    private MirrorRepository mirrorRepository;

    @Inject
    private PublicationRepository publicationRepository;

    @Inject
    private SourceAdapterRegistry sourceAdapterRegistry;

    @Transactional
    public void build(long mirrorId) {
        Mirror mirror = mirrorRepository.lockForBuild(mirrorId).orElse(null);
        if (mirror == null) {
            return;
        }
        SourceAdapter sourceAdapter = channelRepository.find(mirror.channelId())
            .map(channel -> sourceAdapterRegistry.get(channel.sourceType()))
            .orElseThrow(() -> new IllegalStateException("Mirror channel does not exist: " + mirror.channelId()));
        List<Message> messages = messageRepository.findUnassignedForMirror(mirrorId);
        if (messages.isEmpty()) {
            return;
        }
        List<Message> group = new ArrayList<>();
        group.add(messages.getFirst());
        for (int index = 1; index < messages.size(); index++) {
            Message previous = messages.get(index - 1);
            Message next = messages.get(index);
            if (isBoundary(sourceAdapter, previous, next)) {
                publicationRepository.create(mirrorId, group);
                group = new ArrayList<>();
            }
            group.add(next);
        }
        if (isSettled(sourceAdapter, group.getLast())) {
            publicationRepository.create(mirrorId, group);
        }
    }

    private boolean isBoundary(SourceAdapter sourceAdapter, Message previous, Message next) {
        GroupingRelation relation = sourceAdapter.groupingRelation(sourceMessage(previous), sourceMessage(next));
        if (relation == GroupingRelation.MUST_GROUP) {
            return false;
        }
        if (relation == GroupingRelation.MUST_SEPARATE) {
            return true;
        }
        Duration gap = Duration.between(previous.publishedAt(), next.publishedAt());
        return gap.compareTo(sourceAdapter.groupingThreshold()) > 0;
    }

    private boolean isSettled(SourceAdapter sourceAdapter, Message lastMessage) {
        return !lastMessage.publishedAt().plus(sourceAdapter.settlingDelay()).isAfter(clock.instant());
    }

    private SourceMessage sourceMessage(Message message) {
        return new SourceMessage(
            message.sourceId(),
            message.deleted(),
            message.publishedAt(),
            message.body(),
            message.bodyFormat(),
            message.sourceBodyHash(),
            message.sourceData(),
            List.of()
        );
    }

}
