package com.commercefoundry.domainlayer.shippingfee.application.port.out;

import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory;
import org.springframework.data.domain.Page;

/**
 * 배송비 정책 교체 이력 (#69). append-only 라 추가와 조회만 있다 — 고치거나 지우는 메서드를 두지 않는다.
 * {@link ShippingPolicyRepositoryPort} 처럼 범위를 인자로 받지 않는다 — 지금은 국내 이력만 있다.
 */
public interface ShippingPolicyHistoryRepositoryPort {

    /**
     * 이력 한 건과 구간 스냅숏을 바로 INSERT 해 내보낸다(flush). 호출자의 트랜잭션 안에서 돈다 — 실패가 커밋 때가 아니라 이 호출에서 드러나
     * 정책 교체와 함께 롤백된다.
     *
     * @throws org.springframework.dao.DataAccessException 저장 실패 (제약 위반 · 아이디 충돌 등)
     */
    void insert(ShippingPolicyHistory history);

    /**
     * 국내 정책 이력을 아이디(Snowflake — 시각 순) 내림차순, 곧 최신순으로 한 페이지 읽는다. 쿼리는 목록 1 + 전체 건수 1(첫 페이지가 다 차지 않으면 생략)
     * + 그 페이지 이력의 구간 in 절 1 이다. 마지막 페이지를 넘긴 요청은 빈 목록이고 전체 건수는 그대로다.
     *
     * @param page 0 부터 센다
     */
    Page<ShippingPolicyHistory> findDomesticPage(int page, int size);
}
