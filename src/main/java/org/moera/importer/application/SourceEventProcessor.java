package org.moera.importer.application;

import jakarta.inject.Inject;

import org.moera.importer.persistence.ChannelRepository;
import org.moera.importer.persistence.MessageRepository;
import org.moera.importer.source.SourceEvent;
import org.moera.importer.source.SourceEventSink;
import org.springframework.stereotype.Service;

@Service
public class SourceEventProcessor implements SourceEventSink {

    @Inject
    private ChannelRepository channelRepository;

    @Inject
    private MessageRepository messageRepository;

    @Override
    public void accept(SourceEvent event) {
        switch (event) {
            case SourceEvent.MessageChanged changed -> channelRepository.find(
                changed.sourceType(), changed.channelSourceId()
            ).ifPresent(channel -> messageRepository.ingest(channel.id(), changed.message()));
            case SourceEvent.MessageDeleted deleted -> channelRepository.find(
                deleted.sourceType(), deleted.channelSourceId()
            ).ifPresent(channel -> messageRepository.markDeleted(channel.id(), deleted.messageSourceId()));
            case SourceEvent.AccessLost accessLost -> channelRepository.markAccessLost(
                accessLost.sourceType(), accessLost.channelSourceId(), accessLost.data()
            );
            case SourceEvent.SubscriptionLost subscriptionLost -> channelRepository.markSubscriptionLost(
                subscriptionLost.sourceType(), subscriptionLost.channelSourceId(), subscriptionLost.data()
            );
        }
    }

}
