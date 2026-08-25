package org.moera.importer.source;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import org.moera.importer.model.SourceType;

public interface SourceAdapter {

    SourceType type();

    ChannelResolution resolveChannel(URI sourceUri);

    Duration groupingThreshold();

    Duration settlingDelay();

    Duration pollingInterval(ChannelSnapshot channel);

    GroupingRelation groupingRelation(SourceMessage previous, SourceMessage next);

    AccessResult refreshAccess(ChannelSnapshot channel);

    SubscriptionResult refreshSubscription(ChannelSnapshot channel);

    PollResult poll(ChannelSnapshot channel);

    void downloadMedia(ChannelSnapshot channel, MediaSnapshot media, Path destination);

    void start(SourceEventSink sink);

    void stop();

}
