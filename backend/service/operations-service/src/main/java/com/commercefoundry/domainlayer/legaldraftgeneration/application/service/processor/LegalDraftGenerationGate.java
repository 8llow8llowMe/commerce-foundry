package com.commercefoundry.domainlayer.legaldraftgeneration.application.service.processor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LegalDraftGenerationGate {
    private final boolean enabled;

    public LegalDraftGenerationGate(@Value("${operations.legal-draft-generation.enabled:false}") boolean featureEnabled,
                                    @Value("${ai.enabled:false}") boolean aiEnabled) {
        enabled = featureEnabled && aiEnabled;
    }

    public boolean enabled() {
        return enabled;
    }
}
