package com.commercefoundry.domainlayer.settlement.application.port.out;

import com.commercefoundry.domainlayer.settlement.application.exception.SettlementCollectionException;
import com.commercefoundry.domainlayer.settlement.domain.model.PgSettlementItem;
import java.time.LocalDate;
import java.util.List;

/** PG 정산 내역 조회 (#219). PG 응답 타입은 어댑터 밖으로 나오지 않는다. DB 트랜잭션 밖에서 부른다 — 한 페이지에 최대 60초 걸릴 수 있다. */
public interface SettlementGatewayPort {

    /**
     * 매출일 하루의 PG 정산 거래를 모든 페이지에 걸쳐 읽는다. 같은 거래키가 두 번 오면 하나로 합친다(내용이 다르면 계약 위반).
     *
     * @return 금액 부호가 정규화된 거래 — 승인 0 이상, 취소 0 이하. 모두 이 매출일 거래다
     * @throws SettlementCollectionException 수집 실패 — 한 페이지라도 실패하면 전체가 실패다(부분 결과를 돌려주지 않는다)
     */
    List<PgSettlementItem> fetchSettlements(LocalDate soldDate);
}
