package com.commercefoundry.domainlayer.product.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
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

/**
 * 재고 수량 · 재고 관리 여부는 별도 {@code stock} 테이블({@link StockEntity})이 가진다. 여기에 두지 않는다.
 */
@Entity
@Table(
    name = "sku",
    comment = "SKU (상품 × 옵션 값 조합)",
    uniqueConstraints = @UniqueConstraint(name = "uk_sku_product_id_option_value_key", columnNames = {"product_id", "option_value_key"}),
    check = {
        @CheckConstraint(name = "ck_sku_additional_price_non_negative", constraint = "additional_price_amount >= 0"),
        @CheckConstraint(name = "ck_sku_weight_grams_positive", constraint = "weight_grams is null or weight_grams > 0"),
        // 도메인 HsCode 와 같은 규칙(숫자 6~10자리)을 DB 도 막는다 — 운영 SQL 로 '6211.42.00' 이 들어가면 그 SKU 를 읽는 상세 · internal 조회 ·
        // 재고 선점이 매퍼에서 실패(500)한다. 정규식 문법이 H2 와 PostgreSQL 에서 달라 ltrim 으로 앞의 숫자를 걷어낸 나머지가 비었는지 본다
        // (translate(.., '0123456789', '') 는 H2 에서 문자를 지우지 않고 그대로 둬 맞는 값까지 거절한다)
        @CheckConstraint(
            name = "ck_sku_hs_code_format",
            constraint = "hs_code is null or (char_length(hs_code) between 6 and 10 and ltrim(hs_code, '0123456789') = '')"
        )
    }
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

    @Column(name = "weight_grams",
        comment = "배송 무게 (그램, 1 이상) — 해외 배송비 견적에 쓴다. 해외 판매 상품만 필수, null 이면 미입력")
    private Integer weightGrams;

    @Column(name = "hs_code", length = 10,
        comment = "통관 신고용 HS 코드 (숫자 6~10자리) — 해외 판매 상품만 필수, null 이면 미입력")
    private String hsCode;
}
