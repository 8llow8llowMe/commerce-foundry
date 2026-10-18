package com.commercefoundry.domainlayer.shippingfee.application.service;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.response.ShippingPolicyResponse;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.presenter.ShippingFeeWebPresenter;
import com.commercefoundry.domainlayer.shippingfee.application.port.in.ShippingFeeWebUseCase;
import com.commercefoundry.domainlayer.shippingfee.application.service.processor.ShippingPolicyProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ShippingFeeWebFacade implements ShippingFeeWebUseCase {

    private final ShippingPolicyProcessor shippingPolicyProcessor;
    private final ShippingFeeWebPresenter shippingFeeWebPresenter;

    @Override
    @Transactional(readOnly = true)
    public ShippingPolicyResponse getDomesticPolicy() {
        return shippingPolicyProcessor.findDomesticPolicy().map(shippingFeeWebPresenter::toResponse).orElse(null);
    }
}
