package com.commercefoundry.domainlayer.settlement.application.port.out;

import com.commercefoundry.domainlayer.settlement.domain.model.PgSettlementItem;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** PG 정산 거래 저장소 (#219). 호출자의 트랜잭션 안에서 돈다. 조회는 IN 절 · 매출일 한 번씩이다. */
public interface SettlementItemRepositoryPort {

    /**
     * 거래키 기준 upsert — 없는 거래는 새 행(아이디는 어댑터가 Snowflake 로 만든다), 있는 거래는 내용을 이번 수집 값으로 바꾼다. 바로 flush 한다.
     *
     * @throws com.commercefoundry.domainlayer.settlement.application.exception.SettlementException 409 {@code SETTLEMENT_002} — 같은 거래키를 다른 실행이 먼저 넣었다
     */
    void upsertAll(Collection<PgSettlementItem> items, Instant collectedAt);

    /** 매출일의 {@code currency} 거래가 가리키는 결제키(중복 없음). */
    Set<String> findPaymentKeysBySoldDate(LocalDate soldDate, CurrencyCode currency);

    /** 결제키가 목록에 있는 거래 전부(매출일 · 통화 무관). 빈 목록이면 질의하지 않는다. */
    List<PgSettlementItem> findByPaymentKeys(Collection<String> paymentKeys);
}
