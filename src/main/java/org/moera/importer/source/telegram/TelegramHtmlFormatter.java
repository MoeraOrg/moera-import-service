package org.moera.importer.source.telegram;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import it.tdlight.jni.TdApi;
import org.springframework.util.ObjectUtils;

public class TelegramHtmlFormatter {

    public String format(TdApi.FormattedText formattedText) {
        if (formattedText == null || ObjectUtils.isEmpty(formattedText.text)) {
            return "";
        }
        List<EntitySpan> entities = entities(formattedText);
        return renderBlocks(formattedText.text, 0, formattedText.text.length(), entities, Set.of());
    }

    private List<EntitySpan> entities(TdApi.FormattedText formattedText) {
        TdApi.TextEntity[] sourceEntities = formattedText.entities == null
            ? new TdApi.TextEntity[0]
            : formattedText.entities;
        List<EntitySpan> entities = new ArrayList<>(sourceEntities.length);
        for (int index = 0; index < sourceEntities.length; index++) {
            TdApi.TextEntity entity = sourceEntities[index];
            if (entity == null || entity.type == null) {
                throw new IllegalArgumentException("Missing Telegram text entity or entity type");
            }
            int end = entity.offset + entity.length;
            if (entity.offset < 0 || entity.length < 0 || end < entity.offset || end > formattedText.text.length()
                || !isUtf16Boundary(formattedText.text, entity.offset)
                || !isUtf16Boundary(formattedText.text, end)) {
                throw new IllegalArgumentException("Invalid Telegram text entity range");
            }
            entities.add(new EntitySpan(index, entity.offset, end, entity.type));
        }
        return entities;
    }

    private boolean isUtf16Boundary(String text, int offset) {
        return offset == 0 || offset == text.length()
            || !Character.isLowSurrogate(text.charAt(offset))
            || !Character.isHighSurrogate(text.charAt(offset - 1));
    }

    private String renderBlocks(
        String text,
        int requestedStart,
        int requestedEnd,
        List<EntitySpan> entities,
        Set<Integer> excludedBlocks
    ) {
        int start = requestedStart;
        int end = requestedEnd;
        while (start < end && text.charAt(start) == '\n') {
            start++;
        }
        while (end > start && text.charAt(end - 1) == '\n') {
            end--;
        }
        StringBuilder result = new StringBuilder();
        int position = start;
        while (position < end) {
            EntitySpan block = blockStartingAt(text, position, end, entities, excludedBlocks);
            if (block != null) {
                result.append(renderBlock(text, block, entities, excludedBlocks));
                position = block.end();
                continue;
            }

            int paragraphEnd = paragraphEnd(text, position, end);
            int blockStart = nextBlockStart(text, position, paragraphEnd, entities, excludedBlocks);
            int segmentEnd = Math.min(paragraphEnd, blockStart);
            if (segmentEnd > position) {
                result.append("<p>")
                    .append(renderInline(text, position, segmentEnd, entities, excludedBlocks, true))
                    .append("</p>");
                position = segmentEnd;
            }
            if (position == paragraphEnd) {
                position = skipParagraphBoundary(text, position, end);
            }
        }
        return result.toString();
    }

    private EntitySpan blockStartingAt(
        String text,
        int position,
        int end,
        List<EntitySpan> entities,
        Set<Integer> excludedBlocks
    ) {
        return entities.stream()
            .filter(entity -> entity.start() == position && entity.end() <= end)
            .filter(entity -> !excludedBlocks.contains(entity.index()))
            .filter(entity -> isBlock(text, entity, entities))
            .min(Comparator.comparingInt(entity -> blockPriority(entity.type())))
            .orElse(null);
    }

    private int nextBlockStart(
        String text,
        int position,
        int end,
        List<EntitySpan> entities,
        Set<Integer> excludedBlocks
    ) {
        return entities.stream()
            .filter(entity -> entity.start() > position && entity.start() < end)
            .filter(entity -> !excludedBlocks.contains(entity.index()))
            .filter(entity -> isBlock(text, entity, entities))
            .mapToInt(EntitySpan::start)
            .min()
            .orElse(end);
    }

    private int paragraphEnd(String text, int start, int end) {
        for (int position = start; position + 1 < end; position++) {
            if (text.charAt(position) == '\n' && text.charAt(position + 1) == '\n') {
                return position;
            }
        }
        return end;
    }

    private int skipParagraphBoundary(String text, int start, int end) {
        int position = start;
        while (position < end && text.charAt(position) == '\n') {
            position++;
        }
        return position;
    }

    private String renderBlock(
        String text,
        EntitySpan block,
        List<EntitySpan> entities,
        Set<Integer> excludedBlocks
    ) {
        Set<Integer> nestedExcludedBlocks = new HashSet<>(excludedBlocks);
        nestedExcludedBlocks.add(block.index());
        if (isPre(block.type())) {
            return "<pre>" + renderInline(
                text, block.start(), block.end(), entities, nestedExcludedBlocks, false
            ) + "</pre>";
        }
        if (isBlockQuote(block.type())) {
            boolean containsBlocks = hasParagraphBoundary(text, block.start(), block.end())
                || containsNestedBlock(text, block, entities, nestedExcludedBlocks);
            String content = containsBlocks
                ? renderBlocks(text, block.start(), block.end(), entities, nestedExcludedBlocks)
                : renderInline(text, block.start(), block.end(), entities, nestedExcludedBlocks, true);
            return "<blockquote>" + content + "</blockquote>";
        }
        return "<mr-spoiler-block>"
            + renderBlocks(text, block.start(), block.end(), entities, nestedExcludedBlocks)
            + "</mr-spoiler-block>";
    }

    private boolean containsNestedBlock(
        String text,
        EntitySpan outer,
        List<EntitySpan> entities,
        Set<Integer> excludedBlocks
    ) {
        return entities.stream()
            .filter(entity -> entity.start() >= outer.start() && entity.end() <= outer.end())
            .filter(entity -> !excludedBlocks.contains(entity.index()))
            .anyMatch(entity -> isBlock(text, entity, entities));
    }

    private boolean isBlock(String text, EntitySpan entity, List<EntitySpan> entities) {
        if (isPre(entity.type()) || isBlockQuote(entity.type())) {
            return true;
        }
        return entity.type() instanceof TdApi.TextEntityTypeSpoiler
            && (hasParagraphBoundary(text, entity.start(), entity.end())
                || entities.stream()
                    .filter(nested -> nested.index() != entity.index())
                    .filter(nested -> nested.start() >= entity.start() && nested.end() <= entity.end())
                    .anyMatch(nested -> isPre(nested.type()) || isBlockQuote(nested.type())));
    }

    private boolean hasParagraphBoundary(String text, int start, int end) {
        return paragraphEnd(text, start, end) < end;
    }

    private int blockPriority(TdApi.TextEntityType type) {
        return type instanceof TdApi.TextEntityTypeSpoiler ? 0 : 1;
    }

    private String renderInline(
        String text,
        int start,
        int end,
        List<EntitySpan> entities,
        Set<Integer> excludedBlocks,
        boolean lineBreaks
    ) {
        List<Integer> boundaries = new ArrayList<>();
        boundaries.add(start);
        boundaries.add(end);
        entities.stream()
            .filter(entity -> entity.end() > start && entity.start() < end)
            .filter(entity -> !isBlock(text, entity, entities) || excludedBlocks.contains(entity.index()))
            .forEach(entity -> {
                boundaries.add(Math.max(start, entity.start()));
                boundaries.add(Math.min(end, entity.end()));
            });
        List<Integer> orderedBoundaries = boundaries.stream().distinct().sorted().toList();
        List<EntitySpan> active = List.of();
        StringBuilder result = new StringBuilder();
        for (int index = 0; index + 1 < orderedBoundaries.size(); index++) {
            int segmentStart = orderedBoundaries.get(index);
            int segmentEnd = orderedBoundaries.get(index + 1);
            List<EntitySpan> desired = activeEntities(text, segmentStart, segmentEnd, entities, excludedBlocks);
            int common = commonPrefix(active, desired);
            for (int close = active.size() - 1; close >= common; close--) {
                result.append(closeTag(text, active.get(close)));
            }
            for (int open = common; open < desired.size(); open++) {
                result.append(openTag(text, desired.get(open)));
            }
            String escaped = escape(text.substring(segmentStart, segmentEnd));
            result.append(lineBreaks ? escaped.replace("\n", "<br>") : escaped);
            active = desired;
        }
        for (int close = active.size() - 1; close >= 0; close--) {
            result.append(closeTag(text, active.get(close)));
        }
        return result.toString();
    }

    private List<EntitySpan> activeEntities(
        String text,
        int start,
        int end,
        List<EntitySpan> entities,
        Set<Integer> excludedBlocks
    ) {
        return entities.stream()
            .filter(entity -> entity.start() <= start && entity.end() >= end)
            .filter(entity -> !excludedBlocks.contains(entity.index()))
            .filter(entity -> !isBlock(text, entity, entities))
            .filter(entity -> openTag(text, entity) != null)
            .sorted(Comparator.comparingInt(EntitySpan::start)
                .thenComparing(Comparator.comparingInt(EntitySpan::end).reversed())
                .thenComparingInt(EntitySpan::index))
            .toList();
    }

    private int commonPrefix(List<EntitySpan> first, List<EntitySpan> second) {
        int length = Math.min(first.size(), second.size());
        int common = 0;
        while (common < length && first.get(common).index() == second.get(common).index()) {
            common++;
        }
        return common;
    }

    private String openTag(String text, EntitySpan entity) {
        TdApi.TextEntityType type = entity.type();
        if (type instanceof TdApi.TextEntityTypeBold) {
            return "<b>";
        }
        if (type instanceof TdApi.TextEntityTypeItalic) {
            return "<i>";
        }
        if (type instanceof TdApi.TextEntityTypeUnderline) {
            return "<u>";
        }
        if (type instanceof TdApi.TextEntityTypeStrikethrough) {
            return "<s>";
        }
        if (type instanceof TdApi.TextEntityTypeCode) {
            return "<code>";
        }
        if (type instanceof TdApi.TextEntityTypeSpoiler) {
            return "<mr-spoiler>";
        }
        String href = href(text.substring(entity.start(), entity.end()), type);
        return href == null ? null : "<a href=\"" + escapeAttribute(href) + "\">";
    }

    private String closeTag(String text, EntitySpan entity) {
        String openTag = openTag(text, entity);
        if (openTag == null) {
            return "";
        }
        if (openTag.startsWith("<a ")) {
            return "</a>";
        }
        return "</" + openTag.substring(1);
    }

    private String href(String visibleText, TdApi.TextEntityType type) {
        if (type instanceof TdApi.TextEntityTypeUrl) {
            return safeUri(visibleText);
        }
        if (type instanceof TdApi.TextEntityTypeTextUrl textUrl) {
            return safeUri(textUrl.url);
        }
        if (type instanceof TdApi.TextEntityTypeEmailAddress) {
            return safeUri("mailto:" + visibleText);
        }
        if (type instanceof TdApi.TextEntityTypePhoneNumber) {
            String phone = visibleText.replaceAll("[\\p{Z}\\s().-]", "");
            return phone.matches("\\+?[0-9]+") ? safeUri("tel:" + phone) : null;
        }
        if (type instanceof TdApi.TextEntityTypeMention && visibleText.matches("@[A-Za-z0-9_]+")) {
            return "https://t.me/" + visibleText.substring(1);
        }
        return null;
    }

    private String safeUri(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return null;
            }
            return switch (scheme.toLowerCase(Locale.ROOT)) {
                case "http", "https", "mailto", "tel" -> value;
                default -> null;
            };
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean isPre(TdApi.TextEntityType type) {
        return type instanceof TdApi.TextEntityTypePre || type instanceof TdApi.TextEntityTypePreCode;
    }

    private boolean isBlockQuote(TdApi.TextEntityType type) {
        return type instanceof TdApi.TextEntityTypeBlockQuote
            || type instanceof TdApi.TextEntityTypeExpandableBlockQuote;
    }

    private String escape(String text) {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    private String escapeAttribute(String text) {
        return escape(text)
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private record EntitySpan(int index, int start, int end, TdApi.TextEntityType type) {
    }

}
