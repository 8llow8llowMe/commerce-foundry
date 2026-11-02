package com.commercefoundry.domainlayer.product.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/** 정책 쓰기는 SKU 행 잠금 뒤에만 수행한다. 재고 행과 분리해 선점·조정 CAS에 관여하지 않는다. */
@Entity
@Table(name = "stock_alert_policy", comment = "SKU별 품절 임박 기준. 행이 없으면 점검 제외",
    check = @CheckConstraint(name = "ck_stock_alert_policy_threshold_quantity_non_negative", constraint = "threshold_quantity >= 0"))
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class StockAlertPolicyEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "sku_id", comment = "SKU 아이디 (PK · FK: sku.id)")
    private Long skuId;

    @Column(name = "threshold_quantity", nullable = false, comment = "품절 임박 기준 판매 가능 수량 (0 이상, 이하일 때 대상)")
    private long thresholdQuantity;

    @Override
    public Long getId() {
        return skuId;
    }

    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
