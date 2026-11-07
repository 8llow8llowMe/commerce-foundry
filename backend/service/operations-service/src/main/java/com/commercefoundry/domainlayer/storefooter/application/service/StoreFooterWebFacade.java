package com.commercefoundry.domainlayer.storefooter.application.service;

import com.commercefoundry.domainlayer.storefooter.adapter.in.web.dto.response.StoreFooterWebResponses;
import com.commercefoundry.domainlayer.storefooter.adapter.in.web.presenter.StoreFooterWebPresenter;
import com.commercefoundry.domainlayer.storefooter.application.port.in.StoreFooterWebUseCase;
import com.commercefoundry.domainlayer.storefooter.application.service.processor.StoreFooterProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StoreFooterWebFacade implements StoreFooterWebUseCase {
    private final StoreFooterProcessor processor;
    private final StoreFooterWebPresenter presenter;

    @Override
    public StoreFooterWebResponses.Content published(String locale) {
        return presenter.content(processor.published(locale));
    }
}
