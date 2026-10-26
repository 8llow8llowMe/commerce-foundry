package com.commercefoundry.domainlayer.shippingfee.application.port.out;

import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import java.util.Collection;
import java.util.Map;

/**
 * 회원 표시 이름. 값은 이미 마스킹되어 있다 — 원문 이름은 이 포트로 들어오지 않는다 (#322).
 */
public interface MemberDisplayNameQueryPort {

    /**
     * @param memberIds 중복이 있어도 원격 조회는 한 번이다. 빈 목록이면 호출하지 않는다
     * @return 마스킹된 이름. 없는 회원 · 이름이 없는 회원은 값이 null 이거나 키가 없다
     * @throws DeliveryException {@code AUTH_SERVICE_UNAVAILABLE} — auth-service 호출 실패
     */
    Map<Long, String> findMaskedDisplayNames(Collection<Long> memberIds);
}
