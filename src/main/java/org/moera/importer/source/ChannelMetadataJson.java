package org.moera.importer.source;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.util.ObjectUtils;

public final class ChannelMetadataJson {

    private ChannelMetadataJson() {
    }

    public static ObjectNode write(ChannelMetadata metadata) {
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("title", metadata.title());
        value.put("description", metadata.description());
        if (ObjectUtils.isEmpty(metadata.publicUsername())) {
            value.remove("publicUsername");
        } else {
            value.put("publicUsername", metadata.publicUsername());
        }
        if (metadata.emblem() == null) {
            value.remove("emblem");
        } else {
            ObjectNode emblem = value.putObject("emblem");
            emblem.put("remoteId", metadata.emblem().remoteId());
            emblem.put("uniqueId", metadata.emblem().uniqueId());
            emblem.put("mimeType", metadata.emblem().mimeType());
        }
        ArrayNode entities = value.putArray("descriptionEntities");
        metadata.descriptionEntities().forEach(entity -> entities.addObject()
            .put("offset", entity.offset())
            .put("length", entity.length())
            .put("type", entity.type().name()));
        return value;
    }

    public static ChannelMetadata read(JsonNode value) {
        if (!value.isObject() || !value.path("title").isTextual()) {
            return null;
        }
        JsonNode emblemNode = value.path("emblem");
        ChannelMetadata.Emblem emblem = emblemNode.isObject()
            && emblemNode.path("remoteId").isTextual()
            && emblemNode.path("uniqueId").isTextual()
            && emblemNode.path("mimeType").isTextual()
            ? new ChannelMetadata.Emblem(
                emblemNode.path("remoteId").textValue(),
                emblemNode.path("uniqueId").textValue(),
                emblemNode.path("mimeType").textValue()
            )
            : null;
        List<ChannelMetadata.DescriptionEntity> entities = new ArrayList<>();
        for (JsonNode entity : value.path("descriptionEntities")) {
            if (!entity.path("offset").canConvertToInt() || !entity.path("length").canConvertToInt()
                || !entity.path("type").isTextual()) {
                continue;
            }
            try {
                entities.add(new ChannelMetadata.DescriptionEntity(
                    entity.path("offset").intValue(),
                    entity.path("length").intValue(),
                    ChannelMetadata.DescriptionEntityType.valueOf(entity.path("type").textValue())
                ));
            } catch (IllegalArgumentException ignored) {
                // Ignore metadata written by an unsupported or newer entity mapper.
            }
        }
        return new ChannelMetadata(
            value.path("title").textValue(),
            value.path("description").asText(""),
            entities,
            value.path("publicUsername").textValue(),
            emblem
        );
    }

}
