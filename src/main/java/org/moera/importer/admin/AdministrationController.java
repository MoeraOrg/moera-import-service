package org.moera.importer.admin;

import java.net.URI;
import java.util.List;
import jakarta.inject.Inject;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.moera.importer.admin.AdministrationModels.ChannelCreate;
import static org.moera.importer.admin.AdministrationModels.ChannelView;
import static org.moera.importer.admin.AdministrationModels.DestinationCreate;
import static org.moera.importer.admin.AdministrationModels.DestinationView;
import static org.moera.importer.admin.AdministrationModels.MirrorCreate;
import static org.moera.importer.admin.AdministrationModels.MirrorView;

@RestController
@RequestMapping("/api")
public class AdministrationController {

    @Inject
    private AdministrationService administrationService;

    @GetMapping("/channels")
    public List<ChannelView> channels() {
        return administrationService.channels();
    }

    @PostMapping("/channels")
    public ResponseEntity<ChannelView> createChannel(@RequestBody ChannelCreate request) {
        ChannelView created = administrationService.createChannel(request);
        return ResponseEntity.created(URI.create("/api/channels/" + created.id())).body(created);
    }

    @DeleteMapping("/channels/{channelId}")
    public ResponseEntity<Void> deleteChannel(@PathVariable long channelId) {
        administrationService.deleteChannel(channelId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/destinations")
    public List<DestinationView> destinations() {
        return administrationService.destinations();
    }

    @PostMapping("/destinations")
    public ResponseEntity<DestinationView> createDestination(@RequestBody DestinationCreate request) {
        DestinationView created = administrationService.createDestination(request);
        return ResponseEntity.created(URI.create("/api/destinations/" + created.id())).body(created);
    }

    @DeleteMapping("/destinations/{destinationId}")
    public ResponseEntity<Void> deleteDestination(@PathVariable long destinationId) {
        administrationService.deleteDestination(destinationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/mirrors")
    public List<MirrorView> mirrors() {
        return administrationService.mirrors();
    }

    @PostMapping("/mirrors")
    public ResponseEntity<MirrorView> createMirror(@RequestBody MirrorCreate request) {
        MirrorView created = administrationService.createMirror(request);
        return ResponseEntity.created(URI.create("/api/mirrors/" + created.id())).body(created);
    }

    @DeleteMapping("/mirrors/{channelId}/{destinationId}")
    public ResponseEntity<Void> deleteMirror(@PathVariable long channelId, @PathVariable long destinationId) {
        administrationService.deleteMirror(channelId, destinationId);
        return ResponseEntity.noContent().build();
    }

}
