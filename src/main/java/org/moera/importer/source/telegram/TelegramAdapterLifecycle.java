package org.moera.importer.source.telegram;

import org.moera.importer.config.TelegramProperties;
import org.moera.importer.source.SourceEventSink;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "moera.telegram", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "moera.telegram", name = "login", havingValue = "false", matchIfMissing = true)
public class TelegramAdapterLifecycle implements SmartLifecycle {

    private final TelegramSourceAdapter adapter;
    private final TelegramClient client;
    private final SourceEventSink sink;
    private final TelegramProperties properties;
    private volatile boolean running;

    public TelegramAdapterLifecycle(TelegramSourceAdapter adapter, TelegramClient client, SourceEventSink sink,
                                    TelegramProperties properties) {
        this.adapter = adapter;
        this.client = client;
        this.sink = sink;
        this.properties = properties;
    }

    @Override
    public void start() {
        adapter.start(sink);
        client.awaitReady(properties.getReadinessTimeout());
        running = true;
    }

    @Override
    public void stop() {
        adapter.stop();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }

}
