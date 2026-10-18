package com.commercefoundry.domainlayer.shippingfee.application.service;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.ShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.presenter.ShippingPolicyAdminPresenter;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand;
import com.commercefoundry.domainlayer.shippingfee.application.port.in.ShippingPolicyAdminUseCase;
import com.commercefoundry.domainlayer.shippingfee.application.service.processor.ShippingPolicyProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ShippingPolicyAdminFacade implements ShippingPolicyAdminUseCase {

    private final ShippingPolicyProcessor shippingPolicyProcessor;
    private final ShippingPolicyAdminPresenter shippingPolicyAdminPresenter;

    @Override
    @Transactional(readOnly = true)
    public ShippingPolicyAdminResponse getDomesticPolicy() {
        return shippingPolicyProcessor.findDomesticPolicy().map(shippingPolicyAdminPresenter::toResponse).orElse(null);
    }

    /** 정책 행 갱신과 구간 삭제 · 삽입을 한 트랜잭션으로 묶는다 — 실패(버전 충돌 포함)하면 아무것도 바뀌지 않는다. 외부 I/O 가 없다. */
    @Override
    @Transactional
    public ShippingPolicyAdminResponse replaceDomesticPolicy(ShippingPolicyReplaceCommand command) {
        return shippingPolicyAdminPresenter.toResponse(shippingPolicyProcessor.replaceDomesticPolicy(command));
    }
}
