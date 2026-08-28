package org.moera.importer.admin;

import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.moera.importer.model.Destination;
import org.moera.importer.model.Mirror;
import org.moera.importer.persistence.ChannelRepository;
import org.moera.importer.persistence.DestinationRepository;
import org.moera.importer.persistence.MirrorRepository;
import org.moera.importer.source.ChannelResolution;
import org.moera.importer.source.ChannelSnapshot;
import org.moera.importer.source.SourceAdapter;
import org.moera.importer.source.SourceAdapterRegistry;
import org.moera.importer.source.SourcePermanentException;
import org.moera.importer.source.SourceTemporaryException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import static org.moera.importer.admin.AdministrationModels.ChannelCreate;
import static org.moera.importer.admin.AdministrationModels.ChannelView;
import static org.moera.importer.admin.AdministrationModels.DestinationCreate;
import static org.moera.importer.admin.AdministrationModels.DestinationView;
import static org.moera.importer.admin.AdministrationModels.MirrorCreate;
import static org.moera.importer.admin.AdministrationModels.MirrorView;

@Service
public class AdministrationService {

    private static final Pattern ENVIRONMENT_REFERENCE = Pattern.compile("env:[A-Za-z_][A-Za-z0-9_]*");

    @Inject
    private ChannelRepository channelRepository;

    @Inject
    private DestinationRepository destinationRepository;

    @Inject
    private MirrorRepository mirrorRepository;

    @Inject
    private SourceAdapterRegistry sourceAdapterRegistry;

    public List<ChannelView> channels() {
        return channelRepository.findAll().stream().map(this::channelView).toList();
    }

    public ChannelView createChannel(ChannelCreate request) {
        if (request == null || request.sourceType() == null || request.sourceUri() == null
            || request.sourceUri().isBlank()) {
            throw badRequest("Source type and URI are required");
        }
        URI sourceUri;
        try {
            sourceUri = URI.create(request.sourceUri());
        } catch (IllegalArgumentException e) {
            throw badRequest("Source URI is invalid");
        }
        SourceAdapter sourceAdapter;
        try {
            sourceAdapter = sourceAdapterRegistry.get(request.sourceType());
        } catch (IllegalStateException e) {
            throw unavailable("Source adapter is unavailable");
        }
        ChannelResolution resolution;
        try {
            resolution = sourceAdapter.resolveChannel(sourceUri);
        } catch (SourcePermanentException | IllegalArgumentException e) {
            throw badRequest("Source channel cannot be resolved");
        } catch (SourceTemporaryException | IllegalStateException e) {
            throw unavailable("Source channel resolution is temporarily unavailable");
        }
        if (resolution.sourceUri().toString().length() > 4096) {
            throw badRequest("Canonical source URI is too long");
        }
        try {
            return channelView(channelRepository.create(request.sourceType(), resolution));
        } catch (RuntimeException e) {
            if (isIntegrityViolation(e)) {
                throw conflict("Channel already exists");
            }
            throw e;
        }
    }

    public void deleteChannel(long channelId) {
        requirePositive(channelId, "Channel ID is invalid");
        if (!channelRepository.delete(channelId)) {
            throw notFound("Channel does not exist");
        }
    }

    public List<DestinationView> destinations() {
        return destinationRepository.findAll().stream().map(this::destinationView).toList();
    }

    public DestinationView createDestination(DestinationCreate request) {
        validateDestination(request);
        ObjectNode config = objectNode().put("credentialRef", request.credentialRef());
        try {
            return destinationView(destinationRepository.create(request.nodeName(), config));
        } catch (RuntimeException e) {
            if (isIntegrityViolation(e)) {
                throw conflict("Destination already exists");
            }
            throw e;
        }
    }

    public void deleteDestination(long destinationId) {
        requirePositive(destinationId, "Destination ID is invalid");
        if (!destinationRepository.delete(destinationId)) {
            throw notFound("Destination does not exist");
        }
    }

    public MirrorView createMirror(MirrorCreate request) {
        List<String> feeds = validateMirror(request);
        if (channelRepository.find(request.channelId()).isEmpty()) {
            throw notFound("Channel does not exist");
        }
        if (destinationRepository.find(request.destinationId()).isEmpty()) {
            throw notFound("Destination does not exist");
        }
        ObjectNode config = objectNode();
        feeds.forEach(config.putArray("feeds")::add);
        try {
            return mirrorView(mirrorRepository.create(request.channelId(), request.destinationId(), config));
        } catch (RuntimeException e) {
            if (isIntegrityViolation(e)) {
                throw conflict("Mirror already exists or its resources were removed");
            }
            throw e;
        }
    }

    public void deleteMirror(long channelId, long destinationId) {
        requirePositive(channelId, "Channel ID is invalid");
        requirePositive(destinationId, "Destination ID is invalid");
        if (!mirrorRepository.delete(channelId, destinationId)) {
            throw notFound("Mirror does not exist");
        }
    }

    private ChannelView channelView(ChannelSnapshot channel) {
        return new ChannelView(
            channel.id(),
            channel.sourceType(),
            channel.sourceId(),
            channel.sourceUri().toString(),
            channel.accessState(),
            channel.subscriptionState()
        );
    }

    private DestinationView destinationView(Destination destination) {
        return new DestinationView(
            destination.id(),
            destination.nodeName(),
            destination.config().path("credentialRef").textValue()
        );
    }

    private MirrorView mirrorView(Mirror mirror) {
        List<String> feeds = mirror.config().path("feeds").isArray()
            ? mirror.config().path("feeds").valueStream().filter(JsonNode::isTextual)
                .map(JsonNode::textValue).toList()
            : List.of();
        return new MirrorView(mirror.id(), mirror.channelId(), mirror.destinationId(), feeds);
    }

    private void validateDestination(DestinationCreate request) {
        if (request == null || request.nodeName() == null || request.nodeName().isBlank()
            || request.nodeName().length() > 135 || !request.nodeName().equals(request.nodeName().strip())) {
            throw badRequest("Node name is invalid");
        }
        if (!validCredentialReference(request.credentialRef())) {
            throw badRequest("Credential reference is invalid");
        }
    }

    private boolean validCredentialReference(String reference) {
        if (reference == null || reference.isBlank()) {
            return false;
        }
        if (ENVIRONMENT_REFERENCE.matcher(reference).matches()) {
            return true;
        }
        if (!reference.startsWith("file:") || reference.length() == "file:".length()) {
            return false;
        }
        try {
            return Path.of(reference.substring("file:".length())).isAbsolute();
        } catch (InvalidPathException e) {
            return false;
        }
    }

    private List<String> validateMirror(MirrorCreate request) {
        if (request == null) {
            throw badRequest("Mirror configuration is required");
        }
        requirePositive(request.channelId(), "Channel ID is invalid");
        requirePositive(request.destinationId(), "Destination ID is invalid");
        if (request.feeds() == null || request.feeds().isEmpty()) {
            throw badRequest("At least one feed is required");
        }
        List<String> requestedFeeds = request.feeds();
        HashSet<String> unique = new HashSet<>();
        for (String feed : requestedFeeds) {
            if (feed == null || feed.isBlank() || !feed.equals(feed.strip()) || !unique.add(feed)) {
                throw badRequest("Feed names must be non-blank and unique");
            }
        }
        return List.copyOf(requestedFeeds);
    }

    private void requirePositive(long id, String message) {
        if (id <= 0) {
            throw badRequest(message);
        }
    }

    private boolean isIntegrityViolation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof IntegrityConstraintViolationException
                || current instanceof DataIntegrityViolationException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private AdministrationException badRequest(String message) {
        return new AdministrationException(HttpStatus.BAD_REQUEST, message);
    }

    private AdministrationException notFound(String message) {
        return new AdministrationException(HttpStatus.NOT_FOUND, message);
    }

    private AdministrationException conflict(String message) {
        return new AdministrationException(HttpStatus.CONFLICT, message);
    }

    private AdministrationException unavailable(String message) {
        return new AdministrationException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    private static ObjectNode objectNode() {
        return JsonNodeFactory.instance.objectNode();
    }

}
