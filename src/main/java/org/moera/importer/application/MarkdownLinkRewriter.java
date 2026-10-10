package org.moera.importer.application;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.vladsch.flexmark.ast.AutoLink;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.HtmlInline;
import com.vladsch.flexmark.ast.LinkNodeBase;
import com.vladsch.flexmark.ast.RefNode;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import org.jsoup.nodes.Entities;

final class MarkdownLinkRewriter {

    private static final Parser PARSER = Parser.builder().build();
    private static final Pattern HREF = Pattern.compile("(?i)(\\bhref\\s*=\\s*)([\"'])(.*?)\\2");

    private MarkdownLinkRewriter() {
    }

    static String transform(String markdown, UnaryOperator<String> textOperation, UnaryOperator<String> linkOperation) {
        var replacements = new TreeMap<Integer, Replacement>();
        List<RefNode> combinedReferences = new ArrayList<>();
        for (var node : PARSER.parse(markdown).getDescendants()) {
            if (node.getAncestorOfType(HtmlBlock.class) != null) {
                continue;
            }
            if (node instanceof AutoLink link) {
                replaceUrl(link.getText(), linkOperation, replacements);
            } else if (node instanceof LinkNodeBase link) {
                replaceUrl(link.getUrl(), linkOperation, replacements);
                replace(link.getTitle(), textOperation, replacements);
            } else if (node instanceof RefNode reference) {
                if (reference.getFirstChild() == null) {
                    replace(reference.getText(), textOperation, replacements);
                }
                if (reference.isDefined() && reference.isReferenceTextCombined()) {
                    combinedReferences.add(reference);
                }
            } else if (node instanceof HtmlInline) {
                replace(node.getChars(), value -> rewriteHtmlTag(value, linkOperation), replacements);
            } else if (node instanceof HtmlBlock) {
                replace(node.getChars(), value -> TelegramLinkRewriter.transformHtml(
                    value, textOperation, linkOperation
                ), replacements);
            } else if (node instanceof Text || node instanceof Code) {
                replace(node instanceof Code code ? code.getText() : node.getChars(), textOperation, replacements);
            }
        }
        combinedReferences.forEach(reference -> preserveReference(reference, replacements));
        StringBuilder result = new StringBuilder(markdown);
        for (var entry : replacements.descendingMap().entrySet()) {
            result.replace(entry.getKey(), entry.getValue().end(), entry.getValue().text());
        }
        return result.toString();
    }

    private static void preserveReference(RefNode reference, TreeMap<Integer, Replacement> replacements) {
        int start = reference.getStartOffset();
        int end = reference.getEndOffset();
        var edits = replacements.subMap(start, true, end, false);
        if (edits.isEmpty()) {
            return;
        }
        StringBuilder label = new StringBuilder(reference.getChars());
        for (var entry : edits.descendingMap().entrySet()) {
            label.replace(entry.getKey() - start, entry.getValue().end() - start, entry.getValue().text());
        }
        if (reference.getChars().toString().endsWith("[]")) {
            label.setLength(label.length() - 2);
        }
        label.append('[').append(reference.getReference()).append(']');
        edits.clear();
        replacements.put(start, new Replacement(end, label.toString()));
    }

    private static String rewriteHtmlTag(String html, UnaryOperator<String> linkOperation) {
        return HREF.matcher(html).replaceAll(match -> {
            String original = Entities.unescape(match.group(3));
            String replacement = linkOperation.apply(original);
            return Matcher.quoteReplacement(original.equals(replacement) ? match.group()
                : match.group(1) + match.group(2) + Entities.escape(replacement) + match.group(2));
        });
    }

    private static void replaceUrl(BasedSequence chars, UnaryOperator<String> operation,
                                   TreeMap<Integer, Replacement> replacements) {
        String decoded = chars.unescape();
        String replacement = operation.apply(decoded);
        if (!decoded.equals(replacement)) {
            replacements.put(chars.getStartOffset(), new Replacement(chars.getEndOffset(), replacement));
        }
    }

    private static void replace(BasedSequence chars, UnaryOperator<String> operation,
                                TreeMap<Integer, Replacement> replacements) {
        if (chars.isEmpty()) {
            return;
        }
        String original = chars.toString();
        String replacement = operation.apply(original);
        if (!original.equals(replacement)) {
            replacements.put(chars.getStartOffset(), new Replacement(chars.getEndOffset(), replacement));
        }
    }

    private record Replacement(int end, String text) {
    }

}
