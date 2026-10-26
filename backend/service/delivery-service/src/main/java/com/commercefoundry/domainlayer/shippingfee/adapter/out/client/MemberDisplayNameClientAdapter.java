package com.commercefoundry.domainlayer.shippingfee.adapter.out.client;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.client.feign.MemberDisplayNameClient;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.client.support.MemberDisplayNameInternalResponseSupport;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.MemberDisplayNameQueryPort;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MemberDisplayNameClientAdapter implements MemberDisplayNameQueryPort {

    private final MemberDisplayNameClient memberDisplayNameClient;
    private final MemberDisplayNameInternalResponseSupport memberDisplayNameInternalResponseSupport;

    /**
     * 중복을 지우고 한 번 부른다. 배송비 정책 이력 페이지는 최대 20건이라 벌크 상한(100) 안에 든다.
     * 빈 목록이면 auth-service 를 부르지 않는다.
     */
    @Override
    public Map<Long, String> findMaskedDisplayNames(Collection<Long> memberIds) {
        List<Long> distinctIds = memberIds.stream().distinct().toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        return memberDisplayNameInternalResponseSupport.toMaskedNames(
            memberDisplayNameInternalResponseSupport.call(() -> memberDisplayNameClient.getDisplayNames(distinctIds))
        );
    }
}
