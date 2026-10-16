package com.commercefoundry.domainlayer.product.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.persistence.entity.BaseEntity;
import com.commercefoundry.shared.commerce.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 진열 카테고리는 {@link ProductCategoryEntity} 가 가진다. 목록의 카테고리 필터는 그쪽으로 걸므로 {@code category_id} 에는 인덱스를 두지 않는다.
 * 최신순은 PK(Snowflake) 내림차순이라 별도 인덱스가 없다.
 */
@Entity
@Table(
    name = "product",
    comment = "상품",
    indexes = @Index(name = "idx_product_visible_sale_price_amount", columnList = "visible, sale_price_amount")
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProductEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "상품 아이디 (Snowflake)")
    private Long id;

    @Column(name = "category_id", nullable = false,
        comment = "대표 카테고리 아이디 (FK: category.id). 상세 경로 표시용, 진열 카테고리(product_category)에도 반드시 포함")
    private Long categoryId;

    @Column(name = "name", nullable = false, length = 100, comment = "상품명")
    private String name;

    @Column(name = "summary", length = 200, comment = "한 줄 요약")
    private String summary;

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "description_html", comment = "상세 설명 HTML. 표시 전 sanitize 대상")
    private String descriptionHtml;

    @Enumerated(EnumType.STRING)
    @Column(name = "sale_status", nullable = false, length = 20,
        comment = "판매 상태 (ON_SALE / STOP_SELLING). 품절은 저장하지 않는다 — 재고에서 유도")
    private SaleStatus saleStatus;

    @Column(name = "visible", nullable = false, comment = "스토어프론트 노출 여부. false 면 상세 404 · 목록 제외")
    private boolean visible;

    @Column(name = "sale_price_amount", nullable = false, comment = "기본 판매가 (최소 화폐 단위). SKU 추가금의 기준이자 목록 최저가")
    private long salePriceAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "sale_price_currency", nullable = false, length = 3, comment = "기본 판매가 통화")
    private CurrencyCode salePriceCurrency;

    @Column(name = "list_price_amount", comment = "정가 (할인 전, 최소 화폐 단위). null 이면 할인 표시 없음")
    private Long listPriceAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "list_price_currency", length = 3, comment = "정가 통화")
    private CurrencyCode listPriceCurrency;

    @Column(name = "max_quantity_per_order", comment = "1회 주문 최대 수량. null 이면 제한 없음")
    private Integer maxQuantityPerOrder;

    @Column(name = "custom_order", nullable = false, comment = "맞춤 제작 상품 여부")
    private boolean customOrder;

    @Column(name = "custom_order_notice", length = 500, comment = "맞춤 제작 안내 문구")
    private String customOrderNotice;

    @Column(name = "custom_order_lead_time_days", comment = "맞춤 제작 소요일")
    private Integer customOrderLeadTimeDays;
}
