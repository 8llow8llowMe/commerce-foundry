package com.commercefoundry.domainlayer.storefooter.application.port.out.query;

import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooter;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterRevision;

public final class StoreFooterQueryResults {
    private StoreFooterQueryResults() {
    }

    public record Detail(StoreFooter footer, StoreFooterRevision revision) {
    }
}
