package com.commercefoundry.domainlayer.settlement.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.settlement.adapter.out.persistence.entity.PgSettlementItemEntity;
import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PgSettlementItemRepository extends JpaRepository<PgSettlementItemEntity, Long> {

    /** {@code uk_pg_settlement_item_transaction_key} 로 찾는다 — upsert 의 기존 행. 호출자가 목록을 나눠 IN 절 크기를 제한한다. */
    List<PgSettlementItemEntity> findAllByTransactionKeyIn(Collection<String> transactionKeys);

    /** {@code idx_pg_settlement_item_payment_key} — 결제키의 수집된 거래 전부(매출일 무관). 호출자가 목록을 나눠 IN 절 크기를 제한한다. */
    List<PgSettlementItemEntity> findAllByPaymentKeyIn(Collection<String> paymentKeys);

    /** {@code idx_pg_settlement_item_sold_date} — 매출일 거래의 결제키(중복 없음). */
    @Query("select distinct i.paymentKey from PgSettlementItemEntity i where i.soldDate = :soldDate and i.currency = :currency")
    List<String> findPaymentKeysBySoldDate(LocalDate soldDate, CurrencyCode currency);
}
