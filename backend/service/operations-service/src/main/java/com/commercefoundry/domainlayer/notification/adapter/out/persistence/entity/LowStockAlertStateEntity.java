package com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "low_stock_alert_state", comment = "품절 임박 정기 점검 워터마크 (단일 행)", check = {
    @CheckConstraint(name = "ck_low_stock_alert_state_source", constraint = "source = 'PRODUCT'"),
    @CheckConstraint(name = "ck_low_stock_alert_state_alert", constraint = "last_alert_at is null or last_alert_at <= last_accepted_local_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LowStockAlertStateEntity extends BaseEntity {
    public static final String SOURCE = "PRODUCT";

    @Id
    @Column(length = 16, comment = "허용된 관측 원천")
    private String source;
    @Column(nullable = false, comment = "마지막 수락 원천 시각 (첫 페이지 관측 시각)")
    private Instant lastObservedAt;
    @Column(nullable = false, comment = "마지막 수락 로컬 시각")
    private Instant lastAcceptedLocalAt;
    @Column(comment = "마지막 알림 로컬 시각")
    private Instant lastAlertAt;
    @Version
    @Column(nullable = false, comment = "낙관적 락 버전")
    private Long version;

    public LowStockAlertStateEntity(LowStockAlertPolicy.State state) {
        source = SOURCE;
        accept(state);
    }

    public void accept(LowStockAlertPolicy.State state) {
        lastObservedAt = state.lastObservedAt();
        lastAcceptedLocalAt = state.lastAcceptedLocalAt();
        lastAlertAt = state.lastAlertAt();
    }

    public LowStockAlertPolicy.State toDomain() {
        return new LowStockAlertPolicy.State(lastObservedAt, lastAcceptedLocalAt, lastAlertAt);
    }
}
