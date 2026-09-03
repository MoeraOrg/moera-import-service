package org.moera.importer.application;

import jakarta.inject.Inject;

import org.moera.importer.persistence.ChannelRepository;
import org.moera.importer.persistence.MessageRepository;
import org.moera.importer.persistence.IngestResult;
import org.moera.importer.observability.ImportMetrics;
import org.moera.importer.source.SourceEvent;
import org.moera.importer.source.SourceEventSink;
import org.springframework.stereotype.Service;

@Service
public class SourceEventProcessor implements SourceEventSink {

    @Inject
    private ChannelRepository channelRepository;

    @Inject
    private MessageRepository messageRepository;

    @Inject
    private ImportMetrics importMetrics;

    @Inject
    private ProfileSynchronizer profileSynchronizer;

    @Override
    public void accept(SourceEvent event) {
        switch (event) {
            case SourceEvent.MessageChanged changed -> channelRepository.find(
                changed.sourceType(), changed.channelSourceId()
            ).ifPresent(channel -> ingest(channel.id(), changed));
            case SourceEvent.MessageDeleted deleted -> channelRepository.find(
                deleted.sourceType(), deleted.channelSourceId()
            ).ifPresent(channel -> delete(channel.id(), deleted));
            case SourceEvent.ChannelMetadataChanged metadataChanged -> metadata(metadataChanged);
            case SourceEvent.AccessLost accessLost -> channelRepository.markAccessLost(
                accessLost.sourceType(), accessLost.channelSourceId(), accessLost.data()
            );
            case SourceEvent.SubscriptionLost subscriptionLost -> channelRepository.markSubscriptionLost(
                subscriptionLost.sourceType(), subscriptionLost.channelSourceId(), subscriptionLost.data()
            );
        }
    }

    private void metadata(SourceEvent.ChannelMetadataChanged changed) {
        channelRepository.find(changed.sourceType(), changed.channelSourceId()).ifPresent(channel -> {
            if (channelRepository.updateMetadata(
                changed.sourceType(), changed.channelSourceId(), changed.metadata()
            )) {
                profileSynchronizer.synchronize(channel.id());
            }
        });
    }

    private void ingest(long channelId, SourceEvent.MessageChanged changed) {
        IngestResult result = messageRepository.ingest(channelId, changed.message());
        if (result.changed()) {
            importMetrics.messageRevision(result.created() ? "created" : "updated");
        }
    }

    private void delete(long channelId, SourceEvent.MessageDeleted deleted) {
        if (messageRepository.markDeleted(channelId, deleted.messageSourceId())) {
            importMetrics.messageRevision("deleted");
        }
    }

}
