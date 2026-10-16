package com.commercefoundry.domainlayer.product.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.persistence.entity.BaseEntity;
import com.commercefoundry.shared.commerce.CurrencyCode;
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

/**
 * 재고 수량 · 재고 관리 여부는 별도 {@code stock} 테이블이 가진다 (재고 이슈 #10). 여기에 두지 않는다.
 */
@Entity
@Table(
    name = "sku",
    comment = "SKU (상품 × 옵션 값 조합)",
    uniqueConstraints = @UniqueConstraint(name = "uk_sku_product_id_option_value_key", columnNames = {"product_id", "option_value_key"})
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SkuEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "SKU 아이디 (Snowflake)")
    private Long id;

    @Column(name = "product_id", nullable = false, comment = "상품 아이디 (FK: product.id)")
    private Long productId;

    @Column(name = "option_value_key", nullable = false, length = 255,
        comment = "옵션 값 아이디 조합 키 — 값 아이디 오름차순을 쉼표(,)로 이은 문자열. 옵션 없는 상품은 빈 문자열")
    private String optionValueKey;

    @Column(name = "additional_price_amount", nullable = false, comment = "옵션 추가금 (0 이상, 최소 화폐 단위)")
    private long additionalPriceAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "additional_price_currency", nullable = false, length = 3, comment = "추가금 통화 — 상품 기본 판매가 통화와 같아야 한다")
    private CurrencyCode additionalPriceCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "sale_status", nullable = false, length = 20,
        comment = "판매 상태 (ON_SALE / STOP_SELLING). 품절은 저장하지 않는다 — 재고에서 유도")
    private SaleStatus saleStatus;
}
