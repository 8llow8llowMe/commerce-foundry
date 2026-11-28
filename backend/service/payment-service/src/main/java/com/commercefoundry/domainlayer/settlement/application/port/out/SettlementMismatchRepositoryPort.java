package com.commercefoundry.domainlayer.settlement.application.port.out;

import com.commercefoundry.domainlayer.settlement.domain.model.SettlementMismatch;
import java.util.Collection;
import java.util.List;

/** 대사 불일치 저장소 (#219). 호출자의 트랜잭션 안에서 돈다. (결제키, 종류) 유니크 키가 재대사 멱등의 바닥이다. */
public interface SettlementMismatchRepositoryPort {

    /** 결제키가 목록에 있는 불일치 전부(상태 무관). 빈 목록이면 질의하지 않는다. */
    List<SettlementMismatch> findByPaymentKeys(Collection<String> paymentKeys);

    /**
     * 한 번에 저장하고 flush 한다. 버전이 null 이면 INSERT, 아니면 버전 확인 UPDATE 다.
     *
     * @throws com.commercefoundry.domainlayer.settlement.application.exception.SettlementException 409 {@code SETTLEMENT_002} — 다른 날짜의 대사가 같은 (결제키, 종류)를
     *     먼저 넣었거나 버전 충돌이다
     */
    List<SettlementMismatch> saveAll(Collection<SettlementMismatch> mismatches);
}
