package com.commercefoundry.domainlayer.shippingfee.application.port.out;

import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory;
import org.springframework.data.domain.Page;

/**
 * 배송비 정책 교체 이력 (#69). append-only 라 추가와 조회만 있다 — 고치거나 지우는 메서드를 두지 않는다.
 * {@link ShippingPolicyRepositoryPort} 처럼 범위를 인자로 받지 않는다 — 지금은 국내 이력만 있다.
 */
public interface ShippingPolicyHistoryRepositoryPort {

    /** 이력 한 건을 추가한다. 호출자의 트랜잭션 안에서 돌고, 저장 실패는 이 호출에서 예외로 드러난다(커밋 때로 미루지 않는다). */
    void insert(ShippingPolicyHistory history);

    /**
     * 국내 정책 이력을 최신순으로 한 페이지 읽는다. 교체는 버전 검사로 직렬화되므로 아이디(Snowflake) 순서가 교체 순서다.
     * 마지막 페이지를 넘긴 요청은 빈 목록이고 전체 건수는 그대로다.
     *
     * @param page 0 부터 센다
     */
    Page<ShippingPolicyHistory> findDomesticPage(int page, int size);
}
