package com.commercefoundry.domainlayer.shippingfee.application.port.out;

import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingScope;
import java.util.Optional;

/**
 * 배송비 정책과 도서산간 구간. 정책은 배송 범위마다 하나다.
 */
public interface ShippingPolicyRepositoryPort {

    /** 도서산간 구간은 시작 우편번호 오름차순이다. */
    Optional<ShippingPolicy> findByScope(ShippingScope scope);

    /**
     * 정책을 통째로 바꾼다 — 정책 행을 만들거나 고치고, 도서산간 구간은 모두 지운 뒤 새로 넣는다. 호출자의 트랜잭션 안에서 돈다.
     *
     * <ul>
     *   <li>{@code policy.version() == null} — 새로 만든다. 같은 범위의 정책이 그 사이 생겼으면 충돌이다.</li>
     *   <li>그 밖 — {@code policy.id()} 의 현재 버전이 {@code policy.version()} 일 때만 바꾸고 버전을 하나 올린다. 다르면 충돌이다.</li>
     * </ul>
     *
     * @return 저장한 뒤 다시 읽은 정책 — 올라간 버전 · 수정 시각 · 정렬된 구간
     * @throws org.springframework.dao.OptimisticLockingFailureException 충돌. 아무것도 바꾸지 않는다(호출자 트랜잭션이 롤백한다)
     */
    ShippingPolicy replace(ShippingPolicy policy);
}
