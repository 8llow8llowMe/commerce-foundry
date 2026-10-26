package com.commercefoundry.domainlayer.shippingfee.application.service;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item.ShippingPolicyHistoryAdminItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.ShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.presenter.ShippingPolicyAdminPresenter;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand;
import com.commercefoundry.domainlayer.shippingfee.application.port.in.ShippingPolicyAdminUseCase;
import com.commercefoundry.domainlayer.shippingfee.application.service.processor.MemberDisplayNameQueryProcessor;
import com.commercefoundry.domainlayer.shippingfee.application.service.processor.ShippingPolicyProcessor;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory;
import com.commercefoundry.persistence.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ShippingPolicyAdminFacade implements ShippingPolicyAdminUseCase {

    private final ShippingPolicyProcessor shippingPolicyProcessor;
    private final MemberDisplayNameQueryProcessor memberDisplayNameQueryProcessor;
    private final ShippingPolicyAdminPresenter shippingPolicyAdminPresenter;

    @Override
    @Transactional(readOnly = true)
    public ShippingPolicyAdminResponse getDomesticPolicy() {
        return shippingPolicyProcessor.findDomesticPolicy().map(shippingPolicyAdminPresenter::toResponse).orElse(null);
    }

    /**
     * 정책 행 갱신 · 구간 삭제 · 삽입 · 교체 이력 append 를 한 트랜잭션으로 묶는다 — 실패(버전 충돌 · 이력 저장 실패 포함)하면 아무것도 바뀌지 않는다.
     * 외부 I/O 가 없다.
     */
    @Override
    @Transactional
    public ShippingPolicyAdminResponse replaceDomesticPolicy(ShippingPolicyReplaceCommand command) {
        return shippingPolicyAdminPresenter.toResponse(shippingPolicyProcessor.replaceDomesticPolicy(command));
    }

    /**
     * 이력은 DB, 표시 이름은 auth-service Feign 이다. 외부 I/O 가 섞이므로 이 메서드에 트랜잭션을 걸지 않는다.
     * 이력 읽기만 {@link ShippingPolicyProcessor#findDomesticHistories} 가 읽기 전용 트랜잭션으로 좁힌다.
     */
    @Override
    public PageResponse<ShippingPolicyHistoryAdminItem> getDomesticPolicyHistories(int page, int size) {
        Page<ShippingPolicyHistory> histories = shippingPolicyProcessor.findDomesticHistories(page, size);
        return shippingPolicyAdminPresenter.toHistoryPageResponse(histories, memberDisplayNameQueryProcessor.findMaskedNames(histories));
    }
}
