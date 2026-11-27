package com.commercefoundry.domainlayer.storenavigation.application.service;

import com.commercefoundry.domainlayer.storenavigation.adapter.in.web.dto.response.StoreNavigationWebResponses;
import com.commercefoundry.domainlayer.storenavigation.adapter.in.web.presenter.StoreNavigationWebPresenter;
import com.commercefoundry.domainlayer.storenavigation.application.port.in.StoreNavigationWebUseCase;
import com.commercefoundry.domainlayer.storenavigation.application.service.processor.StoreNavigationProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StoreNavigationWebFacade implements StoreNavigationWebUseCase {
    private final StoreNavigationProcessor processor;
    private final StoreNavigationWebPresenter presenter;

    @Override
    public StoreNavigationWebResponses.Content published(String locale) {
        return presenter.content(processor.published(locale));
    }
}
