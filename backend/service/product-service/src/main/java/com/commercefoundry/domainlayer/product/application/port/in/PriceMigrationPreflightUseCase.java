package com.commercefoundry.domainlayer.product.application.port.in;

import com.commercefoundry.domainlayer.product.application.model.PriceMigrationPreflightModel.Report;

/**
 * B1c-B 가격 이행 사전검증. 생산 caller·API·스케줄러·CLI에 연결하지 않는다. 쓰기·backfill을 하지 않는다.
 * 미연결이라 진입 종류 접미사(Admin·Scheduler 등)를 보류했고 연결할 때 진입 종류에 맞게 바꾼다.
 * 호출자 트랜잭션 밖에서 호출한다 — 읽기 adapter가 페이지마다 자체 TX를 쓰므로 바깥 TX는 커넥션만 붙잡는다.
 */
public interface PriceMigrationPreflightUseCase {

    /**
     * 네 원천을 끝까지 읽어 발견 코드별 건수와 판정을 돌려준다.
     *
     * @throws com.commercefoundry.domainlayer.product.application.exception.PriceMigrationReadException 읽기 실패(고정 사유 그대로, 재시도 없음)
     * @throws com.commercefoundry.domainlayer.product.application.exception.PriceMigrationPreflightException 스캔 상한 초과·Port 계약 위반
     */
    Report run();
}
