package com.commercefoundry.domainlayer.storenavigation.application.info;

import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigation;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationContent;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationRevision;
import java.util.List;

public final class StoreNavigationInfos {
    private StoreNavigationInfos() {
    }

    public record Detail(StoreNavigation navigation, StoreNavigationRevision revision) {
    }

    /** 게시본의 요청 언어 공개 트리. 숨김 항목과 그 하위는 이미 빠져 있고 편집용 key 는 없다. */
    public record Published(List<StoreNavigationContent.PublicNode> items) {
    }
}
