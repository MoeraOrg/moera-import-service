package org.moera.importer.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.moera.importer.model.BodyFormat;
import org.moera.importer.model.Message;
import org.moera.importer.moera.DestinationCapabilities;
import org.moera.importer.persistence.MessageLinkPreview;
import org.moera.importer.persistence.PublicationContext;
import org.moera.importer.source.ForwardAttribution;
import org.moera.importer.source.telegram.TelegramForwardAttribution;
import org.moera.lib.node.types.MediaToAttach;
import org.moera.lib.node.types.SourceFormat;
import org.moera.lib.node.types.StoryAttributes;
import org.moera.lib.node.types.body.Body;
import org.moera.lib.node.types.body.LinkPreview;
import org.springframework.stereotype.Component;

@Component
public class PublicationPayloadBuilder {

    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n{2,}");
    private static final Parser MARKDOWN_PARSER = Parser.builder().build();
    private static final HtmlRenderer MARKDOWN_RENDERER = HtmlRenderer.builder().build();

    public PublicationPayload build(PublicationContext context, DestinationCapabilities capabilities) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(capabilities, "capabilities");
        List<PublicationContext.Member> members = context.members().stream()
            .filter(member -> !member.message().deleted())
            .toList();
        SourceFormat format = selectFormat(members, capabilities);
        Body body = new Body();
        body.setText(joinBodies(members, format));
        PreviewSelection previewSelection = previews(members, context.media());
        body.setLinkPreviews(previewSelection.previews());
        List<MediaToAttach> media = attachments(context.media(), previewSelection.ignoredImageMediaIds());
        boolean pinned = members.stream().map(PublicationContext.Member::message).anyMatch(Message::pinned);
        Instant publishedAt = members.stream().map(PublicationContext.Member::message)
            .map(Message::publishedAt).min(Instant::compareTo).orElse(null);

        return new PublicationPayload(
            body, format, media, publications(context.mirrorFeeds(), publishedAt, pinned), pinned,
            externalSourceUri(members)
        );
    }

    private List<String> externalSourceUri(List<PublicationContext.Member> members) {
        return members.stream()
            .map(member -> member.message().externalSourceUri())
            .filter(Objects::nonNull)
            .filter(uri -> !uri.isBlank())
            .toList();
    }

    private SourceFormat selectFormat(List<PublicationContext.Member> members, DestinationCapabilities capabilities) {
        List<BodyFormat> formats = members.stream()
            .map(PublicationContext.Member::message)
            .filter(message -> !message.body().isBlank())
            .map(Message::bodyFormat)
            .distinct()
            .toList();
        if (formats.size() == 1) {
            SourceFormat common = sourceFormat(formats.getFirst());
            if (capabilities.supports(common)) {
                return common;
            }
        }
        if (capabilities.supports(SourceFormat.HTML)) {
            return SourceFormat.HTML;
        }
        return SourceFormat.PLAIN_TEXT;
    }

    private String joinBodies(List<PublicationContext.Member> members, SourceFormat format) {
        var result = new StringBuilder();
        ForwardAttribution previousAttribution = null;
        for (PublicationContext.Member member : members) {
            Message message = member.message();
            ForwardAttribution attribution = message.forwardAttribution();
            if (attribution != null && !TelegramForwardAttribution.sameSource(previousAttribution, attribution)) {
                appendBody(result, convert(attributionHtml(attribution), BodyFormat.HTML, format), format);
            }
            if (!message.body().isBlank()) {
                appendBody(result, convert(message.body(), message.bodyFormat(), format), format);
            }
            previousAttribution = attribution;
        }
        return result.toString();
    }

    private void appendBody(StringBuilder result, String body, SourceFormat format) {
        if (!result.isEmpty() && format != SourceFormat.HTML) {
            result.append("\n\n");
        }
        result.append(body);
    }

    private String attributionHtml(ForwardAttribution attribution) {
        String name = Entities.escape(attribution.name());
        return attribution.uri() != null
            ? "<p><a href=\"" + Entities.escape(attribution.uri()) + "\">" + name + "</a></p>"
            : "<p>" + name + "</p>";
    }

    private String convert(String body, BodyFormat from, SourceFormat to) {
        if (sourceFormat(from) == to) {
            return body;
        }
        if (to == SourceFormat.HTML) {
            return switch (from) {
                case PLAIN_TEXT -> plainTextToHtml(body);
                case MARKDOWN -> markdownToHtml(body);
                case HTML -> body;
            };
        }
        return switch (from) {
            case PLAIN_TEXT -> body;
            case MARKDOWN -> htmlToPlainText(markdownToHtml(body));
            case HTML -> htmlToPlainText(body);
        };
    }

    private String plainTextToHtml(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> paragraphs = PARAGRAPH_BREAK.splitAsStream(normalized)
            .filter(paragraph -> !paragraph.isBlank())
            .map(paragraph -> "<p>" + Entities.escape(paragraph).replace("\n", "<br>") + "</p>")
            .toList();
        return String.join("", paragraphs);
    }

    private String markdownToHtml(String markdown) {
        return MARKDOWN_RENDERER.render(MARKDOWN_PARSER.parse(markdown)).strip();
    }

    private String htmlToPlainText(String html) {
        Element body = Jsoup.parseBodyFragment(html).body();
        body.select("br").before("\n").remove();
        body.select("p,div,blockquote,pre,li,h1,h2,h3,h4,h5,h6").after("\n\n");
        return body.wholeText().strip().replaceAll("\\n[ \\t]+", "\n").replaceAll("\\n{3,}", "\n\n");
    }

    private PreviewSelection previews(List<PublicationContext.Member> members,
                                      List<PublicationContext.DeliveredMedia> deliveredMedia) {
        Map<Long, String> hashes = new HashMap<>();
        for (PublicationContext.DeliveredMedia delivered : deliveredMedia) {
            hashes.put(delivered.media().id(), delivered.destinationMediaHash());
        }
        Map<String, MessageLinkPreview> selected = new LinkedHashMap<>();
        Set<Long> ignoredImages = new HashSet<>();
        for (PublicationContext.Member member : members) {
            for (MessageLinkPreview preview : member.linkPreviews()) {
                MessageLinkPreview previous = selected.putIfAbsent(preview.url(), preview);
                if (previous != null && preview.imageMediaId() != null) {
                    ignoredImages.add(preview.imageMediaId());
                }
            }
        }
        Set<Long> selectedImages = new HashSet<>();
        List<LinkPreview> previews = new ArrayList<>();
        for (MessageLinkPreview source : selected.values()) {
            LinkPreview preview = new LinkPreview();
            preview.setSiteName(source.siteName());
            preview.setUrl(source.url());
            preview.setTitle(source.title());
            preview.setDescription(source.description());
            if (source.publishedAt() != null) {
                preview.setPublishedAt(source.publishedAt().getEpochSecond());
            }
            if (source.imageMediaId() != null) {
                selectedImages.add(source.imageMediaId());
                preview.setImageHash(hashes.get(source.imageMediaId()));
            }
            previews.add(preview);
        }
        ignoredImages.removeAll(selectedImages);
        return new PreviewSelection(List.copyOf(previews), Set.copyOf(ignoredImages));
    }

    private List<MediaToAttach> attachments(List<PublicationContext.DeliveredMedia> deliveredMedia,
                                            Set<Long> ignoredImageMediaIds) {
        List<MediaToAttach> attachments = new ArrayList<>();
        for (PublicationContext.DeliveredMedia delivered : deliveredMedia) {
            if (ignoredImageMediaIds.contains(delivered.media().id())) {
                continue;
            }
            MediaToAttach attachment = new MediaToAttach();
            attachment.setLocalMediaId(delivered.destinationMediaId());
            attachments.add(attachment);
        }
        return List.copyOf(attachments);
    }

    private List<StoryAttributes> publications(List<String> feeds, Instant publishedAt, boolean pinned) {
        if (publishedAt == null) {
            return List.of();
        }
        List<StoryAttributes> publications = new ArrayList<>();
        for (String feed : feeds) {
            StoryAttributes publication = new StoryAttributes();
            publication.setFeedName(feed);
            publication.setPublishAt(publishedAt.getEpochSecond());
            publication.setPinned(pinned);
            publication.setViewed(true);
            publication.setRead(true);
            publications.add(publication);
        }
        return List.copyOf(publications);
    }

    private SourceFormat sourceFormat(BodyFormat bodyFormat) {
        return SourceFormat.valueOf(bodyFormat.name());
    }

    private record PreviewSelection(List<LinkPreview> previews, Set<Long> ignoredImageMediaIds) {
    }

}
