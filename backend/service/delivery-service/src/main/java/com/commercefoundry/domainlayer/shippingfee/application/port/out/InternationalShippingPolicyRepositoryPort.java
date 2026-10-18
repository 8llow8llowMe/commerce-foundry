package com.commercefoundry.domainlayer.shippingfee.application.port.out;

import com.commercefoundry.domainlayer.shippingfee.domain.model.InternationalShippingPolicy;
import java.util.Optional;

/**
 * 해외 배송비 정책({@code shipping_policy} 의 {@code INTERNATIONAL} 행)과 국가 그룹 · 국가 · 무게 구간.
 */
public interface InternationalShippingPolicyRepositoryPort {

    /** 국가 그룹은 표시 순서, 국가는 코드 오름차순, 무게 구간은 상한 오름차순이다. */
    Optional<InternationalShippingPolicy> findPolicy();

    /**
     * 정책을 통째로 바꾼다 — 정책 행을 만들거나 고치고, 국가 그룹 · 국가 · 무게 구간은 모두 지운 뒤 새로 넣는다. 호출자의 트랜잭션 안에서 돈다.
     *
     * <ul>
     *   <li>{@code policy.version() == null} — 새로 만든다. 해외 정책이 그 사이 생겼으면 충돌이다.</li>
     *   <li>그 밖 — {@code policy.id()} 의 현재 버전이 {@code policy.version()} 일 때만 바꾸고 버전을 하나 올린다. 다르면 충돌이다.</li>
     * </ul>
     *
     * @return 저장한 뒤 다시 읽은 정책 — 올라간 버전 · 수정 시각 · 정렬된 하위 행
     * @throws org.springframework.dao.OptimisticLockingFailureException 충돌. 아무것도 바꾸지 않는다(호출자 트랜잭션이 롤백한다)
     */
    InternationalShippingPolicy replace(InternationalShippingPolicy policy);
}
