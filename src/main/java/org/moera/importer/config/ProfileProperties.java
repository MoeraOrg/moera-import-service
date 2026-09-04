package org.moera.importer.config;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

@Component
@ConfigurationProperties(prefix = "moera.profile")
public class ProfileProperties {

    private String contactEmail = "balu@moera.org";

    public String getContactEmail() {
        return contactEmail;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
    }

    @PostConstruct
    void validate() {
        if (ObjectUtils.isEmpty(contactEmail) || contactEmail.isBlank()) {
            throw new IllegalStateException("Moera profile contact e-mail must be configured");
        }
    }

}
