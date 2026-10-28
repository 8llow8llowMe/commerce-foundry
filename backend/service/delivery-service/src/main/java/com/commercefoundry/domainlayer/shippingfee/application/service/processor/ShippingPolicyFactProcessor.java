package com.commercefoundry.domainlayer.shippingfee.application.service.processor;

import com.commercefoundry.domainlayer.shippingfee.application.info.ShippingPolicyFactInfo;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyHistoryRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 운영자 온보딩의 배송 업무 사실 (#357). 업무 정의는 "국내 배송비 정책 저장" 이다 — 해외 정책 교체는 이력(행위자)을 남기지 않아 보지 않는다.
 *
 * <p>스토어 준비(정책 행 유무)와 운영자별 최초 저장(이력)은 따로 판정한다. 다른 운영자가 만든 정책이 있어도 이 운영자의 저장 시각은 null 일 수 있다.
 */
@Component
@RequiredArgsConstructor
public class ShippingPolicyFactProcessor {

    private final ShippingPolicyRepositoryPort shippingPolicyRepositoryPort;
    private final ShippingPolicyHistoryRepositoryPort shippingPolicyHistoryRepositoryPort;

    public ShippingPolicyFactInfo getFact(long memberId) {
        return new ShippingPolicyFactInfo(
            shippingPolicyRepositoryPort.existsDomesticPolicy(),
            shippingPolicyHistoryRepositoryPort.findDomesticFirstSavedAt(memberId).orElse(null)
        );
    }
}
