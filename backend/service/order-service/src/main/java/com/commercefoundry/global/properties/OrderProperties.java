package com.commercefoundry.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 주문 요청 처리 기한 · 회원별 활성 요청 상한. 처리 기한은 중복 처리 완화용이며 원격 작업의 소유권을 보장하지 않는다. */
@ConfigurationProperties(prefix = "order")
public record OrderProperties(@DefaultValue Creation creation) {
    public OrderProperties {
        java.util.Objects.requireNonNull(creation, "order.creation is required");
    }

    /**
     * @param processingLease            같은 키의 처리 중 중복을 막는 기한
     * @param maxActiveRequestsPerMember 회원 한 명이 {@code activeRequestWindow} 안에 새 키로 시작할 수 있는 주문 생성 시도 수 (#231).
     *                                   시도마다 재고 선점이 생기므로 계정 하나로 재고를 묶어 두는 것을 막는다
     * @param activeRequestWindow        활성 시도로 세는 창 — product-service 선점 TTL({@code stock.reservation.ttl})과 같게 둔다
     */
    public record Creation(
        @DefaultValue("30s") Duration processingLease,
        @DefaultValue("5") int maxActiveRequestsPerMember,
        @DefaultValue("30m") Duration activeRequestWindow
    ) {
        public Creation {
            if (processingLease == null || processingLease.compareTo(Duration.ofSeconds(1)) < 0
                || processingLease.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException("order.creation.processing-lease must be between 1 second and 5 minutes");
            }
            if (maxActiveRequestsPerMember < 1) {
                throw new IllegalArgumentException("order.creation.max-active-requests-per-member must be at least 1");
            }
            if (activeRequestWindow == null || activeRequestWindow.compareTo(Duration.ofMinutes(1)) < 0
                || activeRequestWindow.compareTo(Duration.ofHours(24)) > 0) {
                throw new IllegalArgumentException("order.creation.active-request-window must be between 1 minute and 24 hours");
            }
        }
    }
}
