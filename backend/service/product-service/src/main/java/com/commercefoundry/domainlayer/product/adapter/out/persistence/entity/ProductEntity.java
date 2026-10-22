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
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 진열 카테고리는 {@link ProductCategoryEntity} 가 가진다. 목록의 카테고리 필터는 그쪽으로 걸므로 {@code category_id} 에는 인덱스를 두지 않는다.
 * 최신순은 PK(Snowflake) 내림차순이라 별도 인덱스가 없다. 가격순 인덱스는 동률을 끊는 정렬 키({@code id})까지 담는다.
 *
 * <p>check 제약은 금액 · 통화 짝과 음수 금지를 DB 에서도 막는다 — 매퍼({@code MoneyColumns})가 읽을 때 실패하는 데이터가 애초에 들어오지 않게.
 *
 * <p>삭제는 soft delete 다 — {@code deleted_at} 을 채우고 숨김 · 판매 종료로 함께 바꾼다. SKU · 재고 · 옵션 · 이미지 행은 남긴다(주문 · 장바구니가 계속 읽는다).
 * {@code ck_product_deleted_hidden} 이 "삭제 ⇒ 숨김 + 판매 종료" 를 DB 에서 고정해, 삭제를 모르는 경로도 숨김 · 판매 종료로 막힌다.
 * 고객 조회 · 판매 가능 판정은 그래도 삭제 조건을 명시한다 ({@code ProductCustomRepositoryImpl#visibleToCustomer} · {@code SaleAvailability#isOpenForSale}).
 * v1 에는 복구 기능이 없다(운영 SQL).
 *
 * <p>버전이 {@code Long}(wrapper)이라 새 행은 버전이 null 이고, Spring Data 는 그것으로 새 행을 판정해 {@code persist} 한다 ({@code coding-conventions.md} §9-1).
 */
@Entity
@Table(
    name = "product",
    comment = "상품",
    indexes = @Index(name = "idx_product_visible_sale_price_amount_id", columnList = "visible, sale_price_amount, id"),
    check = {
        @CheckConstraint(name = "ck_product_sale_price_non_negative", constraint = "sale_price_amount >= 0"),
        @CheckConstraint(
            name = "ck_product_list_price_pair",
            constraint = "(list_price_amount is null and list_price_currency is null) or (list_price_amount is not null and list_price_currency is not null)"
        ),
        @CheckConstraint(name = "ck_product_list_price_currency", constraint = "list_price_currency is null or list_price_currency = sale_price_currency"),
        @CheckConstraint(name = "ck_product_list_price_non_negative", constraint = "list_price_amount is null or list_price_amount >= 0"),
        @CheckConstraint(name = "ck_product_deleted_hidden", constraint = "deleted_at is null or (visible = false and sale_status = 'STOP_SELLING')")
    }
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

    @Column(name = "deleted_at", comment = "삭제 시각 (soft delete). null 이면 삭제되지 않은 상품. 삭제되면 숨김 · 판매 종료로 고정된다(ck_product_deleted_hidden)")
    private Instant deletedAt;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전")
    private Long version;
}
