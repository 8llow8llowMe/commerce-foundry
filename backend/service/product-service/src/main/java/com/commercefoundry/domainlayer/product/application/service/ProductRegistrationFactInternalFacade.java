package com.commercefoundry.domainlayer.product.application.service;

import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.ProductRegistrationFactInternalResponse;
import com.commercefoundry.domainlayer.product.adapter.in.internal.presenter.ProductRegistrationFactInternalPresenter;
import com.commercefoundry.domainlayer.product.application.port.in.ProductRegistrationFactInternalUseCase;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductRegistrationFactProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductRegistrationFactInternalFacade implements ProductRegistrationFactInternalUseCase {
    private final ProductRegistrationFactProcessor processor;
    private final ProductRegistrationFactInternalPresenter presenter;

    @Override
    @Transactional(readOnly = true)
    public ProductRegistrationFactInternalResponse getFact(long memberId) {
        return presenter.toResponse(processor.getFact(memberId));
    }
}
