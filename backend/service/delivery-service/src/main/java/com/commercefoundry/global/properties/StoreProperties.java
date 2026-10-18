package com.commercefoundry.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 스토어(고객사)별 설정 — 브랜드 차이는 코드 분기가 아니라 이 프로퍼티와 기능 플래그로 표현한다 ({@code architecture-guide.md} §9).
 *
 * <p>{@code store.*} 를 쓰는 두 번째 서비스다(첫째는 product-service 의 {@code store.features.custom-order}). 그래도 common-core 로 올리지 않는다 —
 * 두 서비스가 읽는 플래그가 서로 달라(맞춤 제작 · 해외 배송) "같은 프로퍼티를 쓰게 되면 올린다" 는 조건에 해당하지 않는다.
 * 같은 플래그를 두 서비스가 읽게 되면 그때 올린다.
 *
 * @param features 기능 플래그. yml 에 없으면 모두 꺼진 기본값이다
 */
@ConfigurationProperties(prefix = "store")
public record StoreProperties(Features features) {

    public StoreProperties {
        features = features == null ? Features.DISABLED : features;
    }

    /**
     * 플래그는 {@code Boolean} 으로 받아 빈 값을 꺼짐으로 정규화한다. 배포 환경이 {@code STORE_FEATURES_INTERNATIONAL_SHIPPING=} 처럼 빈 환경변수를 넣으면
     * 플레이스홀더 기본값({@code :false}) 대신 빈 문자열로 풀리는데, primitive {@code boolean} 이면 바인딩이 실패해 기동하지 않는다.
     * 정규화한 뒤에는 null 이 아니다.
     *
     * @param internationalShipping 해외 배송. 꺼지면 해외 정책 데이터는 그대로 두고 고객 안내에서 숨기며(200 + null) KR 외 국가 견적을 거절한다(DELIVERY_002).
     *     어드민 해외 정책 API 는 플래그와 무관하다 — 켜기 전에 미리 준비할 수 있게
     */
    public record Features(Boolean internationalShipping) {

        /** 모두 꺼짐 — 닫힌 기본값 ({@code bootstrap-conventions.md} 설정 파일). */
        public static final Features DISABLED = new Features(false);

        public Features {
            internationalShipping = Boolean.TRUE.equals(internationalShipping);
        }
    }
}
