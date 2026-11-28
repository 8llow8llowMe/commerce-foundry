package com.commercefoundry.domainlayer.settlement.application.port.out;

import com.commercefoundry.domainlayer.settlement.domain.model.SettlementDay;
import java.time.LocalDate;
import java.util.Optional;

/** 매출일 요약 저장소 (#219). 호출자의 트랜잭션 안에서 돈다. */
public interface SettlementDayRepositoryPort {

    /**
     * 매출일 행을 잠가 읽는다(PostgreSQL {@code for no key update nowait}). 같은 매출일의 수집 · 대사 실행이 줄을 서지 않고 둘째가 바로 409 다.
     *
     * @throws com.commercefoundry.domainlayer.settlement.application.exception.SettlementException 409 {@code SETTLEMENT_002} — 다른 실행이 잡고 있다
     */
    Optional<SettlementDay> lockBySoldDate(LocalDate soldDate);

    /**
     * 저장하고 바로 flush 한다. 버전이 null 이면 INSERT, 아니면 버전 확인 UPDATE 다.
     *
     * @throws com.commercefoundry.domainlayer.settlement.application.exception.SettlementException 409 {@code SETTLEMENT_002} — 같은 매출일 행을 다른 실행이 먼저
     *     만들었거나 버전 충돌이다
     */
    SettlementDay save(SettlementDay day);
}
