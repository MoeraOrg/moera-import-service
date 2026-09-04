package org.moera.importer.admin;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import jakarta.inject.Inject;

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
import org.moera.importer.application.ProfileSynchronizer;
import org.moera.importer.source.InitialImportMode;
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

    @Inject
    private ChannelRepository channelRepository;

    @Inject
    private DestinationRepository destinationRepository;

    @Inject
    private MirrorRepository mirrorRepository;

    @Inject
    private ProfileSynchronizer profileSynchronizer;

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
        if (request.fullHistory() && request.initialImportAfter() != null) {
            throw badRequest("Full history and initial import boundary are mutually exclusive");
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
        Instant initialImportAfter = request.initialImportAfter() != null
            ? request.initialImportAfter().truncatedTo(ChronoUnit.SECONDS)
            : null;
        InitialImportMode initialImportMode = initialImportAfter != null
            ? InitialImportMode.AFTER
            : request.fullHistory() ? InitialImportMode.FULL : InitialImportMode.RECENT;
        try {
            resolution = sourceAdapter.resolveChannel(sourceUri, initialImportMode);
        } catch (SourcePermanentException | IllegalArgumentException e) {
            throw badRequest("Source channel cannot be resolved");
        } catch (SourceTemporaryException | IllegalStateException e) {
            throw unavailable("Source channel resolution is temporarily unavailable");
        }
        if (resolution.sourceUri().toString().length() > 4096) {
            throw badRequest("Canonical source URI is too long");
        }
        try {
            return channelView(channelRepository.create(
                request.sourceType(), initialImportMode, initialImportAfter, resolution
            ));
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
        try {
            return destinationView(destinationRepository.create(request.nodeName(), request.profileSync()));
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

    public List<MirrorView> mirrors() {
        return mirrorRepository.findAll().stream().map(this::mirrorView).toList();
    }

    public MirrorView createMirror(MirrorCreate request) {
        List<String> feeds = validateMirror(request);
        if (channelRepository.find(request.channelId()).isEmpty()) {
            throw notFound("Channel does not exist");
        }
        Destination destination = destinationRepository.find(request.destinationId()).orElse(null);
        if (destination == null) {
            throw notFound("Destination does not exist");
        }
        if (profileSyncEnabled(destination) && mirrorRepository.hasMirror(request.destinationId())) {
            throw conflict("Profile-synchronized destination already has a mirror");
        }
        try {
            MirrorView mirror = mirrorView(mirrorRepository.create(
                request.channelId(), request.destinationId(), feeds
            ));
            profileSynchronizer.synchronize(request.channelId());
            return mirror;
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
            destination.profileSync()
        );
    }

    private boolean profileSyncEnabled(Destination destination) {
        return destination.profileSync();
    }

    private MirrorView mirrorView(Mirror mirror) {
        return new MirrorView(mirror.id(), mirror.channelId(), mirror.destinationId(), mirror.feeds());
    }

    private void validateDestination(DestinationCreate request) {
        if (request == null || request.nodeName() == null || request.nodeName().isBlank()
            || request.nodeName().length() > 135 || !request.nodeName().equals(request.nodeName().strip())) {
            throw badRequest("Node name is invalid");
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
