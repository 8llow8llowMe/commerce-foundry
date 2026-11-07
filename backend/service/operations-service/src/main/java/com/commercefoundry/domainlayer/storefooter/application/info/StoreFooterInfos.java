package com.commercefoundry.domainlayer.storefooter.application.info;

import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooter;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterRevision;
import java.util.List;

public final class StoreFooterInfos {
    private StoreFooterInfos() {
    }

    public record Detail(StoreFooter footer, StoreFooterRevision revision) {
    }

    public record Published(String copyrightText, List<Link> links) {
        public record Link(String type, String label, String url) {
        }
    }
}
