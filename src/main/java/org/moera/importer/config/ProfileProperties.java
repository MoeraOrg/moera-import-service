package org.moera.importer.config;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

@Component
@ConfigurationProperties(prefix = "moera.profile")
public class ProfileProperties {

    private String contactEmail = "balu@moera.org";
    private String attributionHtml = "<p>This profile mirrors {channelLink}. To import another channel, or your own "
        + "channel into your Moera account, contact {contactLink}.</p>";

    public String getContactEmail() {
        return contactEmail;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
    }

    public String getAttributionHtml() {
        return attributionHtml;
    }

    public void setAttributionHtml(String attributionHtml) {
        this.attributionHtml = attributionHtml;
    }

    @PostConstruct
    void validate() {
        if (ObjectUtils.isEmpty(contactEmail) || contactEmail.isBlank()) {
            throw new IllegalStateException("Moera profile contact e-mail must be configured");
        }
        if (ObjectUtils.isEmpty(attributionHtml) || attributionHtml.isBlank()) {
            throw new IllegalStateException("Moera profile attribution HTML must be configured");
        }
    }

}
