package org.moera.importer.source.telegram;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.moera.importer.source.SourcePermanentException;

sealed interface TelegramReference {

    Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]+");
    Pattern INVITE_TOKEN = Pattern.compile("[A-Za-z0-9_-]+");
    Set<String> WEB_HOSTS = Set.of("t.me", "www.t.me", "telegram.me", "www.telegram.me");

    URI canonicalUri();

    record Public(String username, URI canonicalUri) implements TelegramReference {
    }

    record Invite(String inviteLink, URI canonicalUri) implements TelegramReference {
    }

    static TelegramReference parse(URI uri) {
        if (uri == null) {
            throw invalid();
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return publicReference(bareReference(uri));
        }
        return switch (scheme.toLowerCase(Locale.ROOT)) {
            case "http", "https" -> webReference(uri);
            case "tg" -> deepLink(uri);
            default -> throw invalid();
        };
    }

    private static String bareReference(URI uri) {
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw invalid();
        }
        String value = uri.toString();
        return value.startsWith("@") ? value.substring(1) : value;
    }

    private static TelegramReference webReference(URI uri) {
        if (uri.getHost() == null
            || !WEB_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
            || uri.getPort() != -1
            || uri.getUserInfo() != null
            || uri.getRawQuery() != null
            || uri.getRawFragment() != null) {
            throw invalid();
        }
        String path = uri.getPath();
        if (path == null || !path.startsWith("/")) {
            throw invalid();
        }
        String reference = path.substring(1);
        if (reference.endsWith("/")) {
            reference = reference.substring(0, reference.length() - 1);
        }
        if (reference.startsWith("+")) {
            return inviteReference(reference.substring(1));
        }
        if (reference.startsWith("joinchat/")) {
            return inviteReference(reference.substring("joinchat/".length()));
        }
        if (reference.startsWith("s/")) {
            reference = reference.substring(2);
        }
        return publicReference(reference);
    }

    private static TelegramReference deepLink(URI uri) {
        if (uri.getHost() == null || uri.getPort() != -1 || uri.getUserInfo() != null
            || uri.getRawFragment() != null || uri.getPath() != null && !uri.getPath().isEmpty()) {
            throw invalid();
        }
        Map<String, String> parameters = queryParameters(uri.getRawQuery());
        return switch (uri.getHost().toLowerCase(Locale.ROOT)) {
            case "resolve" -> publicReference(parameters.get("domain"));
            case "join" -> inviteReference(parameters.get("invite"));
            default -> throw invalid();
        };
    }

    private static Map<String, String> queryParameters(String query) {
        if (query == null) {
            throw invalid();
        }
        Map<String, String> parameters = new HashMap<>();
        for (String part : query.split("&")) {
            int separator = part.indexOf('=');
            if (separator <= 0) {
                throw invalid();
            }
            String name = decode(part.substring(0, separator));
            String value = decode(part.substring(separator + 1));
            parameters.putIfAbsent(name, value);
        }
        return parameters;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private static Public publicReference(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw invalid();
        }
        return new Public(username, URI.create("https://t.me/" + username));
    }

    private static Invite inviteReference(String token) {
        if (token == null || !INVITE_TOKEN.matcher(token).matches()) {
            throw invalid();
        }
        URI canonicalUri = URI.create("https://t.me/+" + token);
        return new Invite(canonicalUri.toString(), canonicalUri);
    }

    private static SourcePermanentException invalid() {
        return new SourcePermanentException("Invalid Telegram channel URI");
    }

}
