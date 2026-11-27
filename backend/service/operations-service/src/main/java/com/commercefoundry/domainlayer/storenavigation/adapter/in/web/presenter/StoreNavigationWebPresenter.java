package com.commercefoundry.domainlayer.storenavigation.adapter.in.web.presenter;

import com.commercefoundry.domainlayer.storenavigation.adapter.in.web.dto.response.StoreNavigationWebResponses;
import com.commercefoundry.domainlayer.storenavigation.application.info.StoreNavigationInfos;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationContent;
import java.util.List;
import org.springframework.stereotype.Component;

/** 매핑만 한다. 노출 필터·순서·언어 선택은 도메인 publicTree 가 정한다. 식별자 long → String 변환은 여기서 한다. */
@Component
public class StoreNavigationWebPresenter {
    public StoreNavigationWebResponses.Content content(StoreNavigationInfos.Published info) {
        return new StoreNavigationWebResponses.Content(items(info.items()));
    }

    private List<StoreNavigationWebResponses.Item> items(List<StoreNavigationContent.PublicNode> nodes) {
        return nodes.stream().map(node -> new StoreNavigationWebResponses.Item(node.label(), node.description(), node.type(), node.url(),
            node.categoryId() == null ? null : Long.toString(node.categoryId()), node.primary(), node.shortcut(), items(node.children()))).toList();
    }
}
