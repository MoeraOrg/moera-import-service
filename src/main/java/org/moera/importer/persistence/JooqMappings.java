package org.moera.importer.persistence;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.JSON;
import org.moera.importer.model.BodyFormat;
import org.moera.importer.model.Destination;
import org.moera.importer.model.Message;
import org.moera.importer.model.Media;
import org.moera.importer.model.Mirror;
import org.moera.importer.persistence.jooq.tables.records.ChannelsRecord;
import org.moera.importer.persistence.jooq.tables.records.DestinationsRecord;
import org.moera.importer.persistence.jooq.tables.records.MessagesRecord;
import org.moera.importer.persistence.jooq.tables.records.MediaRecord;
import org.moera.importer.persistence.jooq.tables.records.MirrorsRecord;
import org.moera.importer.source.ChannelSnapshot;
import org.moera.importer.source.ChannelMetadata;
import org.moera.importer.source.ForwardAttribution;
import org.moera.importer.source.InitialImportMode;
import org.springframework.stereotype.Component;

@Component
public class JooqMappings {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public JSON json(JsonNode value) {
        return value != null ? JSON.valueOf(value.toString()) : null;
    }

    public JsonNode jsonNode(JSON value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value.data());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid JSON read from PostgreSQL", e);
        }
    }

    public OffsetDateTime offsetDateTime(Instant value) {
        return value != null ? value.atOffset(ZoneOffset.UTC) : null;
    }

    public Instant instant(OffsetDateTime value) {
        return value != null ? value.toInstant() : null;
    }

    public org.moera.importer.persistence.jooq.enums.BodyFormat bodyFormat(BodyFormat value) {
        return org.moera.importer.persistence.jooq.enums.BodyFormat.valueOf(value.name());
    }

    public ChannelSnapshot channelSnapshot(ChannelsRecord record) {
        return new ChannelSnapshot(
            record.getId(),
            org.moera.importer.model.SourceType.valueOf(record.getSourceType().getLiteral()),
            record.getSourceId(),
            URI.create(record.getSourceUri()),
            jsonNode(record.getSourceData()),
            InitialImportMode.valueOf(record.getInitialImportMode().getLiteral()),
            instant(record.getInitialImportAfter()),
            channelMetadata(record),
            org.moera.importer.model.AccessState.valueOf(record.getAccessState().getLiteral()),
            jsonNode(record.getAccessData()),
            org.moera.importer.model.SubscriptionState.valueOf(record.getSubscriptionState().getLiteral()),
            jsonNode(record.getSubscriptionData()),
            jsonNode(record.getWaterline())
        );
    }

    public Destination destination(DestinationsRecord record) {
        return new Destination(
            record.getId(),
            record.getNodeName(),
            record.getProfileSync(),
            record.getProfileFingerprint(),
            record.getProfileSourceEmblemId(),
            record.getAvatarId(),
            record.getAvatarMediaId(),
            record.getAvatarShape(),
            instant(record.getCreatedAt()),
            instant(record.getUpdatedAt())
        );
    }

    public Message message(MessagesRecord record) {
        return new Message(
            record.getId(),
            record.getChannelId(),
            record.getSourceId(),
            record.getRevision(),
            record.getDeleted(),
            record.getPinned(),
            instant(record.getPublishedAt()),
            record.getBody(),
            BodyFormat.valueOf(record.getBodyFormat().getLiteral()),
            record.getSourceBodyHash(),
            record.getExternalSourceUri(),
            record.getForwardName() != null
                ? new ForwardAttribution(record.getForwardName(), record.getForwardUri())
                : null,
            jsonNode(record.getSourceData()),
            instant(record.getCreatedAt()),
            instant(record.getUpdatedAt())
        );
    }

    public Media media(MediaRecord record) {
        return new Media(
            record.getId(),
            record.getMessageId(),
            record.getSourceId(),
            record.getPosition(),
            record.getMimeType(),
            record.getFileName(),
            record.getSize(),
            record.getSourceHash(),
            jsonNode(record.getSourceData()),
            instant(record.getCreatedAt()),
            instant(record.getUpdatedAt())
        );
    }

    public Mirror mirror(MirrorsRecord record) {
        return new Mirror(
            record.getId(),
            record.getChannelId(),
            record.getDestinationId(),
            record.getEnabled(),
            List.of(record.getFeeds()),
            instant(record.getCreatedAt()),
            instant(record.getUpdatedAt())
        );
    }

    private ChannelMetadata channelMetadata(ChannelsRecord record) {
        if (record.getTitle() == null) {
            return null;
        }
        ChannelMetadata.Emblem emblem = record.getEmblemRemoteId() != null
            && record.getEmblemUniqueId() != null
            && record.getEmblemMimeType() != null
            ? new ChannelMetadata.Emblem(
                record.getEmblemRemoteId(), record.getEmblemUniqueId(), record.getEmblemMimeType()
            )
            : null;
        return new ChannelMetadata(
            record.getTitle(), record.getDescriptionHtml(), record.getPublicUsername(), emblem
        );
    }

}
