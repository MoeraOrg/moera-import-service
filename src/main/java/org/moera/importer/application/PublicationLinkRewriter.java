package org.moera.importer.application;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import jakarta.inject.Inject;

import org.moera.importer.model.Destination;
import org.moera.importer.persistence.PublicationLinkRepository;
import org.moera.importer.persistence.PublicationLinkRepository.PostingTarget;
import org.moera.importer.source.telegram.TelegramMessageUrl;
import org.moera.lib.UniversalLocation;
import org.moera.lib.node.types.body.Body;
import org.springframework.stereotype.Component;

@Component
public class PublicationLinkRewriter {

    @Inject
    private PublicationLinkRepository publicationLinkRepository;

    public PublicationPayload rewrite(PublicationPayload payload, Destination destination) {
        Set<TelegramMessageUrl> urls = new HashSet<>(
            TelegramLinkRewriter.collect(payload.bodySrc().getText(), payload.bodySrcFormat())
        );
        for (var preview : payload.bodySrc().getLinkPreviews()) {
            TelegramMessageUrl.parse(preview.getUrl()).ifPresent(urls::add);
        }
        if (urls.isEmpty()) {
            return payload;
        }
        Map<TelegramMessageUrl, String> replacements = new HashMap<>();
        publicationLinkRepository.findTargets(urls).forEach((url, targets) -> {
            PostingTarget target = selectTarget(targets, destination.id());
            if (target != null) {
                replacements.put(url, UniversalLocation.redirectTo(
                    target.nodeName(), null, "/post/" + target.postingId(), null, null
                ));
            }
        });
        if (replacements.isEmpty()) {
            return payload;
        }
        Body body = payload.bodySrc().clone();
        body.setText(TelegramLinkRewriter.rewrite(body.getText(), payload.bodySrcFormat(), replacements));
        for (var preview : body.getLinkPreviews()) {
            String rewrittenUrl = TelegramLinkRewriter.rewriteUrl(preview.getUrl(), replacements);
            if (!Objects.equals(preview.getUrl(), rewrittenUrl)) {
                preview.setUrl(rewrittenUrl);
                preview.setSiteName(null);
            }
        }
        return new PublicationPayload(
            body, payload.bodySrcFormat(), payload.media(), payload.publications(), payload.pinned(),
            payload.externalSourceUri()
        );
    }

    private PostingTarget selectTarget(List<PostingTarget> targets, long destinationId) {
        if (targets.stream().map(PostingTarget::messageId).distinct().count() != 1) {
            return null;
        }
        List<PostingTarget> local = targets.stream().filter(target -> target.destinationId() == destinationId).toList();
        List<PostingTarget> candidates = local.isEmpty() ? targets : local;
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

}
