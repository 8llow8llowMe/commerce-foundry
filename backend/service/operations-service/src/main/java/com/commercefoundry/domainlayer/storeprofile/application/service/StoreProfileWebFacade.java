package com.commercefoundry.domainlayer.storeprofile.application.service;

import com.commercefoundry.domainlayer.storeprofile.adapter.in.web.dto.response.StoreProfileWebResponse;
import com.commercefoundry.domainlayer.storeprofile.adapter.in.web.presenter.StoreProfileWebPresenter;
import com.commercefoundry.domainlayer.storeprofile.application.port.in.StoreProfileWebUseCase;
import com.commercefoundry.domainlayer.storeprofile.application.service.processor.StoreProfileProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StoreProfileWebFacade implements StoreProfileWebUseCase {

    private final StoreProfileProcessor processor;

    private final StoreProfileWebPresenter presenter;

    @Override
    public StoreProfileWebResponse get() {
        return presenter.toResponse(processor.get());
    }
}
