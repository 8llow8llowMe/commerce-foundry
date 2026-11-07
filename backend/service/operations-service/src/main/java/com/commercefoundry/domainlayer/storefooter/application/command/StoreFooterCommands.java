package com.commercefoundry.domainlayer.storefooter.application.command;

import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterContent;

public final class StoreFooterCommands {
    private StoreFooterCommands() {
    }

    public record SaveDraft(Long expectedVersion, StoreFooterContent content) {
    }

    public record Publish(Long expectedVersion, Integer revisionNumber) {
    }
}
