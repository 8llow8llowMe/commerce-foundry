package com.commercefoundry.domainlayer.shippingfee.application.service.processor;

import com.commercefoundry.domainlayer.shippingfee.application.port.out.MemberDisplayNameQueryPort;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * 배송비 정책 이력 한 페이지의 관리자 표시 이름을 한 번에 읽는다 (#322). 행마다 조회하지 않는다.
 * 원격 호출이라 트랜잭션을 걸지 않는다.
 */
@Component
@RequiredArgsConstructor
public class MemberDisplayNameQueryProcessor {

    private final MemberDisplayNameQueryPort memberDisplayNameQueryPort;

    public Map<Long, String> findMaskedNames(Page<ShippingPolicyHistory> histories) {
        return memberDisplayNameQueryPort.findMaskedDisplayNames(
            histories.getContent().stream().map(ShippingPolicyHistory::adminMemberId).distinct().toList()
        );
    }
}
