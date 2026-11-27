package com.commercefoundry.domainlayer.storenavigation.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.storenavigation.adapter.in.admin.dto.response.StoreNavigationAdminResponses;
import com.commercefoundry.domainlayer.storenavigation.application.info.StoreNavigationInfos;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationRevision;
import org.springframework.stereotype.Component;

/** 매핑만 한다. 항목 순서(트리 전위)는 저장·조회 쪽이 정해서 넘긴다. 식별자 long → String 변환은 여기서 한다. */
@Component
public class StoreNavigationAdminPresenter {
    public StoreNavigationAdminResponses.Detail detail(StoreNavigationInfos.Detail info) {
        var navigation = info.navigation();
        return new StoreNavigationAdminResponses.Detail(navigation.version(), navigation.latestRevision(), navigation.publishedRevision(),
            revision(info.revision()));
    }

    public StoreNavigationAdminResponses.Revision revision(StoreNavigationRevision revision) {
        var items = revision.content().items().stream()
            .map(item -> new StoreNavigationAdminResponses.Item(item.key(), item.parentKey(), item.type(),
                new StoreNavigationAdminResponses.Text(item.labels().ko(), item.labels().en()),
                item.descriptions() == null ? null : new StoreNavigationAdminResponses.Text(item.descriptions().ko(), item.descriptions().en()),
                item.url(), item.categoryId() == null ? null : Long.toString(item.categoryId()), item.displayOrder(), item.visible(),
                item.primary(), item.shortcut()))
            .toList();
        return new StoreNavigationAdminResponses.Revision(revision.revisionNumber(), items, Long.toString(revision.createdBy()), revision.createdAt(),
            revision.publishedBy() == null ? null : Long.toString(revision.publishedBy()), revision.publishedAt());
    }
}
