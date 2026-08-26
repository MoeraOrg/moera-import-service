package org.moera.importer.persistence;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import jakarta.inject.Inject;

import org.jooq.DSLContext;
import org.moera.importer.model.Message;
import org.moera.importer.persistence.jooq.tables.records.MediaRecord;
import org.moera.importer.persistence.jooq.tables.records.MessagesRecord;
import org.moera.importer.source.PollResult;
import org.moera.importer.source.SourceMedia;
import org.moera.importer.source.SourceMessage;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import static org.moera.importer.persistence.jooq.enums.ChannelOperation.POLL;
import static org.moera.importer.persistence.jooq.tables.Channels.CHANNELS;
import static org.moera.importer.persistence.jooq.tables.Media.MEDIA;
import static org.moera.importer.persistence.jooq.tables.Messages.MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Mirrors.MIRRORS;
import static org.moera.importer.persistence.jooq.tables.PublicationMessages.PUBLICATION_MESSAGES;
import static org.moera.importer.persistence.jooq.tables.Publications.PUBLICATIONS;

@Repository
public class MessageRepository {

    @Inject
    private DSLContext dslContext;

    @Inject
    private JooqMappings jooqMappings;

    @Inject
    private PublicationMediaRepository publicationMediaRepository;

    @Inject
    private PublicationRepository publicationRepository;

    @Transactional
    public IngestResult ingest(long channelId, SourceMessage message) {
        lockChannel(channelId);
        MessagesRecord existing = dslContext.selectFrom(MESSAGES)
            .where(MESSAGES.CHANNEL_ID.eq(channelId))
            .and(MESSAGES.SOURCE_ID.eq(message.sourceId()))
            .forUpdate()
            .fetchOne();
        if (existing != null) {
            List<MediaRecord> mediaRecords = findMedia(existing.getId());
            boolean messageChanged = messageStateChanged(existing, message);
            boolean mediaChanged = mediaStateChanged(mediaRecords, message.media());
            if (!messageChanged && !mediaChanged) {
                refreshMessageMetadata(existing.getId(), message);
                refreshMediaMetadata(mediaRecords, message.media());
                return new IngestResult(existing.getId(), existing.getRevision(), false, false);
            }

            int revision = existing.getRevision() + 1;
            List<Long> mediaWithChangedSourceHash = mediaWithChangedSourceHash(mediaRecords, message.media());
            updateMessage(existing.getId(), revision, message);
            if (mediaChanged) {
                synchronizeMedia(existing.getId(), mediaRecords, message.media());
            } else {
                refreshMediaMetadata(mediaRecords, message.media());
            }
            publicationRepository.reviseForMessage(existing.getId());
            publicationMediaRepository.synchronizeForMessage(existing.getId(), mediaWithChangedSourceHash);
            return new IngestResult(existing.getId(), revision, false, true);
        }

        MessagesRecord record = dslContext.insertInto(MESSAGES)
            .set(MESSAGES.CHANNEL_ID, channelId)
            .set(MESSAGES.SOURCE_ID, message.sourceId())
            .set(MESSAGES.REVISION, 0)
            .set(MESSAGES.DELETED, message.deleted())
            .set(MESSAGES.PUBLISHED_AT, jooqMappings.offsetDateTime(message.publishedAt()))
            .set(MESSAGES.BODY, message.body())
            .set(MESSAGES.BODY_FORMAT, jooqMappings.bodyFormat(message.bodyFormat()))
            .set(MESSAGES.SOURCE_BODY_HASH, message.sourceBodyHash())
            .set(MESSAGES.SOURCE_DATA, jooqMappings.json(message.sourceData()))
            .returning()
            .fetchSingle();

        message.media().forEach(sourceMedia -> insertMedia(record.getId(), sourceMedia));

        return new IngestResult(record.getId(), record.getRevision(), true, true);
    }

    @Transactional
    public boolean markDeleted(long channelId, String sourceMessageId) {
        lockChannel(channelId);
        Long messageId = dslContext.update(MESSAGES)
            .set(MESSAGES.DELETED, true)
            .set(MESSAGES.REVISION, MESSAGES.REVISION.plus(1))
            .set(MESSAGES.UPDATED_AT, jooqMappings.offsetDateTime(Instant.now()))
            .where(MESSAGES.CHANNEL_ID.eq(channelId))
            .and(MESSAGES.SOURCE_ID.eq(sourceMessageId))
            .and(MESSAGES.DELETED.isFalse())
            .returningResult(MESSAGES.ID)
            .fetchOne(MESSAGES.ID);
        if (messageId == null) {
            return false;
        }
        publicationRepository.reviseForMessage(messageId);
        return true;
    }

    public List<Message> findUnassignedForMirror(long mirrorId) {
        return dslContext.selectFrom(MESSAGES)
            .where(MESSAGES.CHANNEL_ID.eq(dslContext.select(MIRRORS.CHANNEL_ID)
                .from(MIRRORS)
                .where(MIRRORS.ID.eq(mirrorId))))
            .andNotExists(dslContext.selectOne()
                .from(PUBLICATION_MESSAGES)
                .join(PUBLICATIONS).on(PUBLICATION_MESSAGES.PUBLICATION_ID.eq(PUBLICATIONS.ID))
                .where(PUBLICATION_MESSAGES.MESSAGE_ID.eq(MESSAGES.ID))
                .and(PUBLICATIONS.MIRROR_ID.eq(mirrorId)))
            .orderBy(MESSAGES.PUBLISHED_AT, MESSAGES.ID)
            .fetch(jooqMappings::message);
    }

    @Transactional
    public boolean ingestPoll(long channelId, UUID processingToken, PollResult result, Instant nextPollAt) {
        Long lockedChannelId = dslContext.select(CHANNELS.ID)
            .from(CHANNELS)
            .where(CHANNELS.ID.eq(channelId))
            .and(CHANNELS.PROCESSING_OPERATION.eq(POLL))
            .and(CHANNELS.PROCESSING_TOKEN.eq(processingToken))
            .forUpdate()
            .fetchOne(CHANNELS.ID);
        if (lockedChannelId == null) {
            return false;
        }

        result.messages().forEach(message -> ingest(channelId, message));
        int updated = dslContext.update(CHANNELS)
            .set(CHANNELS.WATERLINE, jooqMappings.json(result.waterline()))
            .set(CHANNELS.LAST_POLL_AT, jooqMappings.offsetDateTime(Instant.now()))
            .set(CHANNELS.NEXT_POLL_AT, jooqMappings.offsetDateTime(nextPollAt))
            .set(CHANNELS.POLL_ATTEMPTS, 0)
            .setNull(CHANNELS.POLL_LAST_ERROR)
            .setNull(CHANNELS.PROCESSING_OPERATION)
            .setNull(CHANNELS.PROCESSING_TOKEN)
            .setNull(CHANNELS.PROCESSING_UNTIL)
            .set(CHANNELS.UPDATED_AT, jooqMappings.offsetDateTime(Instant.now()))
            .where(CHANNELS.ID.eq(channelId))
            .and(CHANNELS.PROCESSING_OPERATION.eq(POLL))
            .and(CHANNELS.PROCESSING_TOKEN.eq(processingToken))
            .execute();
        if (updated != 1) {
            throw new IllegalStateException("Poll lease changed while its channel row was locked");
        }
        return true;
    }

    private void lockChannel(long channelId) {
        boolean found = dslContext.select(CHANNELS.ID)
            .from(CHANNELS)
            .where(CHANNELS.ID.eq(channelId))
            .forUpdate()
            .fetchOptional()
            .isPresent();
        if (!found) {
            throw new IllegalArgumentException("Channel does not exist: " + channelId);
        }
    }

    private List<MediaRecord> findMedia(long messageId) {
        return dslContext.selectFrom(MEDIA)
            .where(MEDIA.MESSAGE_ID.eq(messageId))
            .orderBy(MEDIA.POSITION)
            .fetch();
    }

    private boolean messageStateChanged(MessagesRecord record, SourceMessage message) {
        return record.getDeleted() != message.deleted()
            || !Arrays.equals(record.getSourceBodyHash(), message.sourceBodyHash());
    }

    private boolean mediaStateChanged(List<MediaRecord> records, List<SourceMedia> sourceMedia) {
        List<SourceMedia> orderedSourceMedia = sourceMedia.stream()
            .sorted(Comparator.comparingInt(SourceMedia::position))
            .toList();
        if (records.size() != orderedSourceMedia.size()) {
            return true;
        }
        for (int index = 0; index < records.size(); index++) {
            MediaRecord record = records.get(index);
            SourceMedia media = orderedSourceMedia.get(index);
            if (!record.getSourceId().equals(media.sourceId())
                || record.getPosition() != media.position()
                || !Arrays.equals(record.getSourceHash(), media.sourceHash())) {
                return true;
            }
        }
        return false;
    }

    private List<Long> mediaWithChangedSourceHash(List<MediaRecord> records, List<SourceMedia> sourceMedia) {
        Map<String, SourceMedia> sourceMediaBySourceId = sourceMedia.stream()
            .collect(Collectors.toMap(SourceMedia::sourceId, Function.identity()));
        return records.stream()
            .filter(record -> sourceMediaBySourceId.containsKey(record.getSourceId()))
            .filter(record -> !Arrays.equals(
                record.getSourceHash(), sourceMediaBySourceId.get(record.getSourceId()).sourceHash()))
            .map(MediaRecord::getId)
            .toList();
    }

    private void synchronizeMedia(long messageId, List<MediaRecord> records, List<SourceMedia> sourceMedia) {
        Map<String, MediaRecord> recordsBySourceId = records.stream()
            .collect(Collectors.toMap(MediaRecord::getSourceId, Function.identity()));
        Map<String, SourceMedia> sourceMediaBySourceId = sourceMedia.stream()
            .collect(Collectors.toMap(SourceMedia::sourceId, Function.identity()));

        records.stream()
            .filter(record -> !sourceMediaBySourceId.containsKey(record.getSourceId()))
            .forEach(record -> dslContext.deleteFrom(MEDIA).where(MEDIA.ID.eq(record.getId())).execute());

        int maximumPosition = Math.max(
            records.stream().mapToInt(MediaRecord::getPosition).max().orElse(-1),
            sourceMedia.stream().mapToInt(SourceMedia::position).max().orElse(-1)
        );
        int temporaryOffset = maximumPosition + 1;
        records.stream()
            .filter(record -> sourceMediaBySourceId.containsKey(record.getSourceId()))
            .filter(record -> record.getPosition() != sourceMediaBySourceId.get(record.getSourceId()).position())
            .forEach(record -> dslContext.update(MEDIA)
                .set(MEDIA.POSITION, record.getPosition() + temporaryOffset)
                .where(MEDIA.ID.eq(record.getId()))
                .execute());

        for (SourceMedia media : sourceMedia) {
            MediaRecord record = recordsBySourceId.get(media.sourceId());
            if (record == null) {
                insertMedia(messageId, media);
            } else if (mediaChanged(record, media)) {
                updateMedia(record.getId(), media);
            }
        }
    }

    private void refreshMessageMetadata(long messageId, SourceMessage message) {
        dslContext.update(MESSAGES)
            .set(MESSAGES.PUBLISHED_AT, jooqMappings.offsetDateTime(message.publishedAt()))
            .set(MESSAGES.BODY, message.body())
            .set(MESSAGES.BODY_FORMAT, jooqMappings.bodyFormat(message.bodyFormat()))
            .set(MESSAGES.SOURCE_DATA, jooqMappings.json(message.sourceData()))
            .set(MESSAGES.UPDATED_AT, jooqMappings.offsetDateTime(Instant.now()))
            .where(MESSAGES.ID.eq(messageId))
            .execute();
    }

    private void updateMessage(long messageId, int revision, SourceMessage message) {
        dslContext.update(MESSAGES)
            .set(MESSAGES.REVISION, revision)
            .set(MESSAGES.DELETED, message.deleted())
            .set(MESSAGES.PUBLISHED_AT, jooqMappings.offsetDateTime(message.publishedAt()))
            .set(MESSAGES.BODY, message.body())
            .set(MESSAGES.BODY_FORMAT, jooqMappings.bodyFormat(message.bodyFormat()))
            .set(MESSAGES.SOURCE_BODY_HASH, message.sourceBodyHash())
            .set(MESSAGES.SOURCE_DATA, jooqMappings.json(message.sourceData()))
            .set(MESSAGES.UPDATED_AT, jooqMappings.offsetDateTime(Instant.now()))
            .where(MESSAGES.ID.eq(messageId))
            .execute();
    }

    private void refreshMediaMetadata(List<MediaRecord> records, List<SourceMedia> sourceMedia) {
        Map<String, SourceMedia> sourceMediaBySourceId = sourceMedia.stream()
            .collect(Collectors.toMap(SourceMedia::sourceId, Function.identity()));
        records.stream()
            .filter(record -> mediaMetadataChanged(record, sourceMediaBySourceId.get(record.getSourceId())))
            .forEach(record -> updateMedia(record.getId(), sourceMediaBySourceId.get(record.getSourceId())));
    }

    private boolean mediaChanged(MediaRecord record, SourceMedia media) {
        return record.getPosition() != media.position()
            || !Arrays.equals(record.getSourceHash(), media.sourceHash())
            || mediaMetadataChanged(record, media);
    }

    private boolean mediaMetadataChanged(MediaRecord record, SourceMedia media) {
        return !record.getMimeType().equals(media.mimeType())
            || !Objects.equals(record.getFileName(), media.fileName())
            || !Objects.equals(record.getSize(), media.size())
            || !jooqMappings.jsonNode(record.getSourceData()).equals(media.sourceData());
    }

    private void updateMedia(long mediaId, SourceMedia media) {
        dslContext.update(MEDIA)
            .set(MEDIA.POSITION, media.position())
            .set(MEDIA.MIME_TYPE, media.mimeType())
            .set(MEDIA.FILE_NAME, media.fileName())
            .set(MEDIA.SIZE, media.size())
            .set(MEDIA.SOURCE_HASH, media.sourceHash())
            .set(MEDIA.SOURCE_DATA, jooqMappings.json(media.sourceData()))
            .set(MEDIA.UPDATED_AT, jooqMappings.offsetDateTime(Instant.now()))
            .where(MEDIA.ID.eq(mediaId))
            .execute();
    }

    private void insertMedia(long messageId, SourceMedia media) {
        dslContext.insertInto(MEDIA)
            .set(MEDIA.MESSAGE_ID, messageId)
            .set(MEDIA.SOURCE_ID, media.sourceId())
            .set(MEDIA.POSITION, media.position())
            .set(MEDIA.MIME_TYPE, media.mimeType())
            .set(MEDIA.FILE_NAME, media.fileName())
            .set(MEDIA.SIZE, media.size())
            .set(MEDIA.SOURCE_HASH, media.sourceHash())
            .set(MEDIA.SOURCE_DATA, jooqMappings.json(media.sourceData()))
            .execute();
    }

}
