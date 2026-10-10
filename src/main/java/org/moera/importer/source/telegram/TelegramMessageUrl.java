package org.moera.importer.source.telegram;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.util.ObjectUtils;

public record TelegramMessageUrl(String channel, long messageId) {

    private static final Pattern MESSAGE_PATH = Pattern.compile(
        "/(?:s/)?(c/[0-9]+|[A-Za-z0-9_]+)/(?:[0-9]+/)?([0-9]+)/?"
    );
    private static final Pattern CHANNEL = Pattern.compile("[A-Za-z0-9_]+|c/[1-9][0-9]*");
    private static final long CHANNEL_CHAT_ID_OFFSET = 1_000_000_000_000L;

    public static Optional<TelegramMessageUrl> parse(String value) {
        if (ObjectUtils.isEmpty(value)) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null) {
                uri = URI.create(uri.getRawAuthority() != null ? "https:" + value : "https://" + value);
            } else if ("tg".equalsIgnoreCase(uri.getScheme()) && uri.isOpaque()) {
                uri = URI.create("tg://" + uri.getRawSchemeSpecificPart());
            }
            if (uri.getScheme() == null || uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() != -1) {
                return Optional.empty();
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if ("tg".equals(scheme)) {
                return parseDeepLink(uri);
            }
            if (!("http".equals(scheme) || "https".equals(scheme))) {
                return Optional.empty();
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath();
            if (!TelegramReference.WEB_HOSTS.contains(host)
                && !"telegram.dog".equals(host) && !"www.telegram.dog".equals(host)) {
                if (!host.endsWith(".t.me")) {
                    return Optional.empty();
                }
                path = "/" + host.substring(0, host.length() - ".t.me".length()) + path;
            }
            if (queryParameters(uri.getRawQuery()).containsKey("comment")) {
                return Optional.empty();
            }
            var matcher = MESSAGE_PATH.matcher(path);
            return matcher.matches() ? normalized(matcher.group(1), matcher.group(2)) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<TelegramMessageUrl> parseDeepLink(URI uri) {
        if (!ObjectUtils.isEmpty(uri.getPath()) || uri.getRawQuery() == null) {
            return Optional.empty();
        }
        Map<String, String> parameters = queryParameters(uri.getRawQuery());
        if (parameters.containsKey("comment")) {
            return Optional.empty();
        }
        return switch (uri.getHost().toLowerCase(Locale.ROOT)) {
            case "resolve" -> normalized(parameters.get("domain"), parameters.get("post"));
            case "privatepost" -> normalized("c/" + parameters.get("channel"), parameters.get("post"));
            default -> Optional.empty();
        };
    }

    private static Map<String, String> queryParameters(String query) {
        Map<String, String> parameters = new HashMap<>();
        if (ObjectUtils.isEmpty(query)) {
            return parameters;
        }
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            if (parameters.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("Duplicate Telegram link parameter");
            }
        }
        return parameters;
    }

    private static Optional<TelegramMessageUrl> normalized(String channel, String messageId) {
        if (channel == null || messageId == null || !CHANNEL.matcher(channel).matches()) {
            return Optional.empty();
        }
        if (channel.startsWith("c/") && Long.parseLong(channel.substring(2)) >= CHANNEL_CHAT_ID_OFFSET) {
            return Optional.empty();
        }
        long id = Long.parseLong(messageId);
        if (id <= 0 || id > Long.MAX_VALUE >> 20) {
            return Optional.empty();
        }
        return Optional.of(new TelegramMessageUrl(channel.toLowerCase(Locale.ROOT), id));
    }

    public String toUri() {
        return "https://t.me/" + channel + "/" + messageId;
    }

    public String toSourceId() {
        return Long.toString(messageId << 20);
    }

    public boolean privateChannel() {
        return channel.startsWith("c/");
    }

    public String toChannelSourceId() {
        return privateChannel() ? Long.toString(-CHANNEL_CHAT_ID_OFFSET - Long.parseLong(channel.substring(2))) : null;
    }

}
