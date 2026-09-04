package org.moera.importer.application;

import jakarta.inject.Inject;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.moera.importer.config.ProfileProperties;
import org.moera.importer.source.ChannelMetadata;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

@Component
public class ChannelProfileRenderer {

    private static final int MAX_FULL_NAME = 96;
    private static final int MAX_BIO = 4096;

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
        String description = truncateHtml(metadata.descriptionHtml(), MAX_BIO - attribution.length());
        String bio = description + attribution;
        return new RenderedChannelProfile(fullName, bio);
    }

    private String channelLink(ChannelMetadata metadata) {
        if (ObjectUtils.isEmpty(metadata.publicUsername())) {
            return "Telegram channel &quot;" + escape(metadata.title()) + "&quot;";
        }
        String url = "https://t.me/" + metadata.publicUsername();
        return "Telegram channel " + "<a href=\"" + escape(url) + "\">" + escape(metadata.title()) + "</a>";
    }

    private String contactLink() {
        String email = escape(profileProperties.getContactEmail());
        return "<a href=\"mailto:" + email + "\">" + email + "</a>";
    }

    private String escape(String value) {
        return Entities.escape(value);
    }

    private String truncate(String value, int maxCodePoints) {
        if (maxCodePoints <= 0) {
            return "";
        }
        int count = value.codePointCount(0, value.length());
        return count <= maxCodePoints ? value : value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private String truncateHtml(String value, int maxCodePoints) {
        if (maxCodePoints <= 0) {
            return "";
        }
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        var document = Jsoup.parseBodyFragment(value);
        document.outputSettings().prettyPrint(false);
        Element body = document.body();
        while (codePointCount(body.html()) > maxCodePoints) {
            TextNode textNode = lastNonEmptyTextNode(body);
            if (textNode != null) {
                int excess = codePointCount(body.html()) - maxCodePoints;
                int textLength = codePointCount(textNode.getWholeText());
                int retainedLength = Math.max(0, textLength - excess);
                textNode.text(truncate(textNode.getWholeText(), retainedLength));
                if (retainedLength == 0) {
                    textNode.remove();
                }
                continue;
            }
            Node lastNode = lastDescendant(body);
            if (lastNode == null) {
                return "";
            }
            lastNode.remove();
        }
        return body.html();
    }

    private TextNode lastNonEmptyTextNode(Element body) {
        TextNode result = null;
        for (Element element : body.getAllElements()) {
            for (TextNode textNode : element.textNodes()) {
                if (!textNode.getWholeText().isEmpty()) {
                    result = textNode;
                }
            }
        }
        return result;
    }

    private Node lastDescendant(Element body) {
        Node node = body;
        while (node.childNodeSize() > 0) {
            node = node.childNode(node.childNodeSize() - 1);
        }
        return node == body ? null : node;
    }

    private int codePointCount(String value) {
        return value.codePointCount(0, value.length());
    }

}
