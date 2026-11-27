package com.commercefoundry.domainlayer.storenavigation.application.command;

import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationContent;

public final class StoreNavigationCommands {
    private StoreNavigationCommands() {
    }

    public record SaveDraft(Long expectedVersion, StoreNavigationContent content) {
    }
}
