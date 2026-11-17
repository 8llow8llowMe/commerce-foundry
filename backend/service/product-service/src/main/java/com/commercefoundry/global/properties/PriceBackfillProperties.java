package com.commercefoundry.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 기준통화 가격행 backfill 기동 실행 설정 (#20 PR-2a). 기본은 OFF 이고, 운영 APPLY 는 사용자 승인 뒤에만 켠다.
 * 환경 변수 {@code PRICE_BACKFILL_MODE} 로 덮어쓴다. 실행이 끝나면 다시 OFF 로 돌린다(재기동마다 다시 돌지 않게).
 *
 * @param mode OFF(아무것도 안 함) · DRY_RUN(사전 점검 + 계획 건수만, DML 없음) · APPLY(사전 점검 READY 일 때만 실제 INSERT). null 이면 OFF
 */
@ConfigurationProperties(prefix = "price-backfill")
public record PriceBackfillProperties(Mode mode) {

    public enum Mode { OFF, DRY_RUN, APPLY }

    public PriceBackfillProperties {
        mode = mode == null ? Mode.OFF : mode;
    }
}
