package com.commercefoundry.domainlayer.storefooter.adapter.in.web.presenter;

import com.commercefoundry.domainlayer.storefooter.adapter.in.web.dto.response.StoreFooterWebResponses;
import com.commercefoundry.domainlayer.storefooter.application.info.StoreFooterInfos;
import org.springframework.stereotype.Component;

@Component
public class StoreFooterWebPresenter {
    public StoreFooterWebResponses.Content content(StoreFooterInfos.Published info) {
        var links = info.links().stream().map(link -> new StoreFooterWebResponses.Link(link.type(), link.label(), link.url())).toList();
        return new StoreFooterWebResponses.Content(info.copyrightText(), links);
    }
}
