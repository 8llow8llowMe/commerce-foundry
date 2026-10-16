package com.commercefoundry.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 스토어(고객사)별 설정 — 브랜드 차이는 코드 분기가 아니라 이 프로퍼티와 기능 플래그로 표현한다 ({@code architecture-guide.md} §9).
 *
 * <p>지금은 product-service 만 쓰므로 서비스 {@code global/properties} 에 둔다. 두 번째 서비스가 쓰게 되면 common-core 로 올린다.
 *
 * @param features 기능 플래그. yml 에 없으면 모두 꺼진 기본값이다
 */
@ConfigurationProperties(prefix = "store")
public record StoreProperties(Features features) {

    public StoreProperties {
        features = features == null ? Features.DISABLED : features;
    }

    /**
     * 플래그는 {@code Boolean} 으로 받아 빈 값을 꺼짐으로 정규화한다. 배포 환경이 {@code STORE_FEATURES_CUSTOM_ORDER=} 처럼 빈 환경변수를 넣으면
     * 플레이스홀더 기본값({@code :false}) 대신 빈 문자열로 풀리는데, primitive {@code boolean} 이면 바인딩이 실패해 기동하지 않는다.
     * 정규화한 뒤에는 null 이 아니다.
     *
     * @param customOrder 맞춤 제작(치수 입력 · 제작 안내). 꺼지면 데이터는 그대로 두고 응답에서만 숨긴다 — 목록 {@code false}, 상세 {@code null}
     */
    public record Features(Boolean customOrder) {

        /** 모두 꺼짐 — 닫힌 기본값 ({@code bootstrap-conventions.md} 설정 파일). */
        public static final Features DISABLED = new Features(false);

        public Features {
            customOrder = Boolean.TRUE.equals(customOrder);
        }
    }
}
