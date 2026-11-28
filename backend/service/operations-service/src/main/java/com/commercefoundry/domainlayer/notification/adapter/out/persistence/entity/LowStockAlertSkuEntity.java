package com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 품절 임박 알림을 이미 보낸 SKU. 행이 있으면 다시 알리지 않고, 완전 스캔에서 해소 판정 기간 이상 빠지면 지운다. */
@Entity
@Table(name = "low_stock_alert_sku", comment = "품절 임박 알림을 보낸 SKU 추적", check = {
    @CheckConstraint(name = "ck_low_stock_alert_sku_id", constraint = "sku_id > 0")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LowStockAlertSkuEntity extends BaseEntity {
    @Id
    @Column(name = "sku_id", comment = "SKU (외부 FK: product-service sku.id)")
    private Long skuId;
    @Column(comment = "완전 스캔에서 처음 빠진 로컬 시각. 다시 보이면 비운다")
    private Instant missingSince;
    @Version
    @Column(nullable = false, comment = "낙관적 락 버전")
    private Long version;

    public LowStockAlertSkuEntity(long skuId) {
        this.skuId = skuId;
    }

    public void markMissing(Instant at) {
        Objects.requireNonNull(at, "at");
        if (missingSince != null) {
            throw new IllegalStateException("Low stock alert sku already missing");
        }
        missingSince = at;
    }

    public void clearMissing() {
        if (missingSince == null) {
            throw new IllegalStateException("Low stock alert sku is not missing");
        }
        missingSince = null;
    }
}
