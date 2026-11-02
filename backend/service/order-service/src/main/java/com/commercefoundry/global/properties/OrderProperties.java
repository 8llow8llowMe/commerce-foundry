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
     * @param processingLease            같은 키의 처리 중 중복을 막는 기한. 원격 호출 타임아웃 합 + 저장 트랜잭션 timeout 보다 길어야 한다
     * @param maxActiveRequestsPerMember 회원 한 명이 {@code activeRequestWindow} 안에 새 키로 시작할 수 있는 주문 생성 시도 수 (#231).
     *                                   시도마다 재고 선점이 생기므로 계정 하나로 재고를 묶어 두는 것을 막는다
     * @param activeRequestWindow        활성 시도로 세는 창 — product-service 선점 TTL({@code stock.reservation.ttl})과 같게 둔다. {@code processingLease} 이상이다
     * @param completionMargin           완료(기록 COMPLETED + 주문 INSERT)는 lease 가 이만큼 더 남아 있을 때만 한다 (#436). 저장 트랜잭션 timeout(5초) + 인스턴스 간
     *                                   시계 차보다 크게 둔다 — 완료 커밋이 lease 끝 뒤로 밀리면 다른 시도의 수량 계수가 이 시도를 "lease 끝남" 으로 빼고 주문도 아직
     *                                   못 봐 상한을 넘겨 통과할 수 있다. 0 보다 크고 {@code processingLease} 보다 작다
     */
    public record Creation(
        @DefaultValue("30s") Duration processingLease,
        @DefaultValue("5") int maxActiveRequestsPerMember,
        @DefaultValue("30m") Duration activeRequestWindow,
        @DefaultValue("10s") Duration completionMargin
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
            if (activeRequestWindow.compareTo(processingLease) < 0) {
                throw new IllegalArgumentException("order.creation.active-request-window must not be shorter than order.creation.processing-lease");
            }
            if (completionMargin == null || !completionMargin.isPositive() || completionMargin.compareTo(processingLease) >= 0) {
                throw new IllegalArgumentException("order.creation.completion-margin must be positive and shorter than order.creation.processing-lease");
            }
        }
    }
}
