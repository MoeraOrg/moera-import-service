package org.moera.importer.application;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import jakarta.inject.Inject;

import org.jsoup.nodes.Entities;
import org.moera.importer.config.ProfileProperties;
import org.moera.importer.source.ChannelMetadata;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

@Component
public class ChannelProfileRenderer {

    private static final int MAX_FULL_NAME = 96;
    private static final int MAX_BIO = 4096;
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n\\s*\\n");

    @Inject
    private ProfileProperties profileProperties;

    public RenderedChannelProfile render(ChannelMetadata metadata) {
        String fullName = truncate(metadata.title(), MAX_FULL_NAME);
        String attribution = profileProperties.getAttributionHtml()
            .replace("{channelLink}", channelLink(metadata))
            .replace("{contactLink}", contactLink());
        if (attribution.length() > MAX_BIO) {
            throw new IllegalStateException("Configured Moera profile attribution is too long");
        }
        String description = metadata.description();
        String bio = descriptionHtml(description, metadata.descriptionEntities()) + attribution;
        while (bio.length() > MAX_BIO && !description.isEmpty()) {
            description = description.substring(0, description.offsetByCodePoints(0, description.codePointCount(0,
                description.length()) - 1));
            bio = descriptionHtml(description, metadata.descriptionEntities()) + attribution;
        }
        return new RenderedChannelProfile(fullName, bio);
    }

    private String channelLink(ChannelMetadata metadata) {
        String label = "Telegram channel &quot;" + escape(metadata.title()) + "&quot;";
        if (ObjectUtils.isEmpty(metadata.publicUsername())) {
            return label;
        }
        String url = "https://t.me/" + metadata.publicUsername();
        return "<a href=\"" + escape(url) + "\">" + label + "</a>";
    }

    private String contactLink() {
        String email = escape(profileProperties.getContactEmail());
        return "<a href=\"mailto:" + email + "\">" + email + "</a>";
    }

    private String descriptionHtml(
        String description,
        List<ChannelMetadata.DescriptionEntity> descriptionEntities
    ) {
        if (ObjectUtils.isEmpty(description) || description.isBlank()) {
            return "";
        }
        String linked = linkedDescription(description, descriptionEntities)
            .replace("\r\n", "\n")
            .replace('\r', '\n');
        List<String> paragraphs = PARAGRAPH_BREAK.splitAsStream(linked)
            .filter(paragraph -> !paragraph.isBlank())
            .map(paragraph -> "<p>" + paragraph.replace("\n", "<br>") + "</p>")
            .toList();
        return String.join("", paragraphs);
    }

    private String linkedDescription(
        String description,
        List<ChannelMetadata.DescriptionEntity> descriptionEntities
    ) {
        StringBuilder html = new StringBuilder();
        int position = 0;
        List<ChannelMetadata.DescriptionEntity> entities = descriptionEntities.stream()
            .filter(entity -> entity.offset() >= 0 && entity.offset() + entity.length() <= description.length())
            .sorted(Comparator.comparingInt(ChannelMetadata.DescriptionEntity::offset))
            .toList();
        for (ChannelMetadata.DescriptionEntity entity : entities) {
            if (entity.offset() < position) {
                continue;
            }
            html.append(escape(description.substring(position, entity.offset())));
            String visible = description.substring(entity.offset(), entity.offset() + entity.length());
            html.append("<a href=\"")
                .append(escape(entityHref(entity.type(), visible)))
                .append("\">")
                .append(escape(visible))
                .append("</a>");
            position = entity.offset() + entity.length();
        }
        return html.append(escape(description.substring(position))).toString();
    }

    private String entityHref(ChannelMetadata.DescriptionEntityType type, String visible) {
        return switch (type) {
            case URL -> visible;
            case EMAIL -> "mailto:" + visible;
            case MENTION -> "https://t.me/" + visible.substring(1);
        };
    }

    private String escape(String value) {
        return Entities.escape(value);
    }

    private String truncate(String value, int maxCodePoints) {
        int count = value.codePointCount(0, value.length());
        return count <= maxCodePoints ? value : value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

}
