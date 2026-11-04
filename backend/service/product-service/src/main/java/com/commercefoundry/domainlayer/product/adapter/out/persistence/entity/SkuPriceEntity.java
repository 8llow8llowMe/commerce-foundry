package com.commercefoundry.domainlayer.product.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.product.domain.model.SkuCurrencyPrice;
import com.commercefoundry.persistence.entity.BaseEntity;
import com.commercefoundry.shared.commerce.CurrencyCode;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "sku_price", comment = "SKU 통화별 추가금 (기존 읽기·쓰기 미연결)",
    uniqueConstraints = @UniqueConstraint(name = "uk_sku_price_sku_id_additional_price_currency", columnNames = {"sku_id", "additional_price_currency"}),
    check = {
        @CheckConstraint(name = "ck_sku_price_ids_positive", constraint = "id > 0 and sku_id > 0"),
        @CheckConstraint(name = "ck_sku_price_additional_non_negative", constraint = "additional_price_amount >= 0")
    })
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SkuPriceEntity extends BaseEntity implements Persistable<Long> {
    @Id
    @Column(name = "id", comment = "SKU 가격 아이디 (Snowflake)")
    private Long id;

    @Column(name = "sku_id", nullable = false, updatable = false, comment = "SKU 아이디 (FK: sku.id)")
    private Long skuId;

    @Column(name = "additional_price_amount", nullable = false, comment = "옵션 추가금 (최소 화폐 단위, 0 이상)")
    private long additionalPriceAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "additional_price_currency", nullable = false, updatable = false, length = 3, comment = "옵션 추가금 통화")
    private CurrencyCode additionalPriceCurrency;

    public void requireIdentity(SkuCurrencyPrice price) {
        if (id != price.id() || skuId != price.skuId() || additionalPriceCurrency != price.additionalPrice().currency()) {
            throw new IllegalArgumentException("SKU 가격 식별자·소속·통화는 변경할 수 없습니다");
        }
    }

    public void replaceAmount(SkuCurrencyPrice price) {
        requireIdentity(price);
        additionalPriceAmount = price.additionalPrice().amount();
    }

    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
