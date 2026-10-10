package org.moera.importer.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.moera.importer.source.telegram.TelegramMessageUrl;
import org.moera.lib.node.types.SourceFormat;
import org.springframework.util.ObjectUtils;

final class TelegramLinkRewriter {

    private static final Pattern URL = Pattern.compile(
        "(?i)(?<![a-z0-9_/@.-])(?:https?://|tg:(?://)?|(?:www\\.)?(?:t\\.me|telegram\\.me|telegram\\.dog)/"
            + "|[a-z0-9_]+\\.t\\.me/)[^\\s<>\"'`]+"
    );
    private static final String TRAILING_PUNCTUATION = ".,;:!?)]}";

    private TelegramLinkRewriter() {
    }

    static Set<TelegramMessageUrl> collect(String text, SourceFormat format) {
        Set<TelegramMessageUrl> urls = new HashSet<>();
        transform(text, format, value -> {
            var matcher = URL.matcher(value);
            while (matcher.find()) {
                TelegramMessageUrl.parse(trimPunctuation(matcher.group())).ifPresent(urls::add);
            }
            return value;
        }, value -> {
            TelegramMessageUrl.parse(value).ifPresent(urls::add);
            return value;
        });
        return Set.copyOf(urls);
    }

    static String rewrite(String text, SourceFormat format, Map<TelegramMessageUrl, String> targets) {
        if (targets.isEmpty()) {
            return text;
        }
        return transform(text, format, value -> replace(value, targets), value -> rewriteUrl(value, targets));
    }

    static String rewriteUrl(String uri, Map<TelegramMessageUrl, String> targets) {
        return TelegramMessageUrl.parse(uri).map(targets::get).orElse(uri);
    }

    private static String replace(String text, Map<TelegramMessageUrl, String> targets) {
        return URL.matcher(text).replaceAll(match -> {
            String original = match.group();
            String uri = trimPunctuation(original);
            String target = TelegramMessageUrl.parse(uri).map(targets::get).orElse(null);
            return java.util.regex.Matcher.quoteReplacement(
                target != null ? target + original.substring(uri.length()) : original
            );
        });
    }

    private static String trimPunctuation(String value) {
        int end = value.length();
        while (end > 0 && TRAILING_PUNCTUATION.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(0, end);
    }

    private static String transform(String text, SourceFormat format, UnaryOperator<String> operation,
                                    UnaryOperator<String> linkOperation) {
        if (ObjectUtils.isEmpty(text)) {
            return text;
        }
        if (format == SourceFormat.MARKDOWN) {
            return MarkdownLinkRewriter.transform(text, operation, linkOperation);
        }
        if (format != SourceFormat.HTML) {
            return operation.apply(text);
        }
        return transformHtml(text, operation, linkOperation);
    }

    static String transformHtml(String text, UnaryOperator<String> operation, UnaryOperator<String> linkOperation) {
        var document = Jsoup.parseBodyFragment(text);
        document.outputSettings().prettyPrint(false);
        boolean[] changed = { false };
        List<TextNode> run = new ArrayList<>();
        transformNodes(document.body(), run, operation, linkOperation, changed);
        transformRun(run, operation, changed);
        return changed[0] ? document.body().html() : text;
    }

    private static void transformNodes(Node parent, List<TextNode> run, UnaryOperator<String> operation,
                                       UnaryOperator<String> linkOperation, boolean[] changed) {
        for (Node node : parent.childNodes()) {
            if (node instanceof TextNode textNode) {
                run.add(textNode);
            } else if (node instanceof Element element) {
                boolean boundary = textBoundary(element);
                if (boundary) {
                    transformRun(run, operation, changed);
                }
                if (element.hasAttr("href")) {
                    String original = element.attr("href");
                    String replacement = linkOperation.apply(original);
                    if (!original.equals(replacement)) {
                        element.attr("href", replacement);
                        changed[0] = true;
                    }
                }
                transformNodes(element, run, operation, linkOperation, changed);
                if (boundary) {
                    transformRun(run, operation, changed);
                }
            } else {
                transformRun(run, operation, changed);
            }
        }
    }

    private static boolean textBoundary(Element element) {
        return element.tag().isBlock() || "br".equals(element.normalName());
    }

    private static void transformRun(List<TextNode> nodes, UnaryOperator<String> operation, boolean[] changed) {
        StringBuilder text = new StringBuilder();
        nodes.stream().map(TextNode::getWholeText).forEach(text::append);
        var matcher = URL.matcher(text);
        List<TextReplacement> replacements = new ArrayList<>();
        while (matcher.find()) {
            String original = matcher.group();
            String replacement = operation.apply(original);
            if (!original.equals(replacement)) {
                replacements.add(new TextReplacement(matcher.start(), matcher.end(), replacement));
            }
        }
        for (TextReplacement replacement : replacements.reversed()) {
            replaceSpan(nodes, replacement);
            changed[0] = true;
        }
        nodes.clear();
    }

    private static void replaceSpan(List<TextNode> nodes, TextReplacement replacement) {
        int offset = 0;
        int replacementOffset = 0;
        for (TextNode node : nodes) {
            String original = node.getWholeText();
            int nodeEnd = offset + original.length();
            if (nodeEnd > replacement.start() && offset < replacement.end()) {
                int start = Math.max(0, replacement.start() - offset);
                int end = Math.min(original.length(), replacement.end() - offset);
                int replacementEnd = nodeEnd >= replacement.end() ? replacement.text().length()
                    : Math.min(replacement.text().length(), replacementOffset + end - start);
                node.text(original.substring(0, start)
                    + replacement.text().substring(replacementOffset, replacementEnd) + original.substring(end));
                replacementOffset = replacementEnd;
            }
            offset = nodeEnd;
            if (offset >= replacement.end()) {
                break;
            }
        }
    }

    private record TextReplacement(int start, int end, String text) {
    }

}
