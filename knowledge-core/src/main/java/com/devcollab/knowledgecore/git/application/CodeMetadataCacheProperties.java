package com.devcollab.knowledgecore.git.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "devcollab.code-metadata-cache")
public record CodeMetadataCacheProperties(boolean enabled, int capacity) {

    public CodeMetadataCacheProperties {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
    }
}
