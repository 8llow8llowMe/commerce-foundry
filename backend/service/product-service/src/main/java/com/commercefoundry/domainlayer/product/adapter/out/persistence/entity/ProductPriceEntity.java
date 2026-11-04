package com.commercefoundry.domainlayer.product.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.product.domain.model.ProductCurrencyPrice;
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
@Table(name = "product_price", comment = "상품 통화별 가격 (기존 읽기·쓰기 미연결)",
    uniqueConstraints = @UniqueConstraint(name = "uk_product_price_product_id_sale_price_currency", columnNames = {"product_id", "sale_price_currency"}),
    check = {
        @CheckConstraint(name = "ck_product_price_ids_positive", constraint = "id > 0 and product_id > 0"),
        @CheckConstraint(name = "ck_product_price_sale_non_negative", constraint = "sale_price_amount >= 0"),
        @CheckConstraint(name = "ck_product_price_list_pair", constraint =
            "(list_price_amount is null and list_price_currency is null) or (list_price_amount is not null and list_price_currency is not null)"),
        @CheckConstraint(name = "ck_product_price_list_currency", constraint = "list_price_currency is null or list_price_currency = sale_price_currency"),
        @CheckConstraint(name = "ck_product_price_list_above_sale", constraint = "list_price_amount is null or list_price_amount > sale_price_amount")
    })
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProductPriceEntity extends BaseEntity implements Persistable<Long> {
    @Id
    @Column(name = "id", comment = "상품 가격 아이디 (Snowflake)")
    private Long id;

    @Column(name = "product_id", nullable = false, updatable = false, comment = "상품 아이디 (FK: product.id)")
    private Long productId;

    @Column(name = "sale_price_amount", nullable = false, comment = "기본 판매가 (최소 화폐 단위, 0 이상)")
    private long salePriceAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "sale_price_currency", nullable = false, updatable = false, length = 3, comment = "기본 판매가 통화")
    private CurrencyCode salePriceCurrency;

    @Column(name = "list_price_amount", comment = "정가 (최소 화폐 단위). null이면 정가 없음")
    private Long listPriceAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "list_price_currency", length = 3, comment = "정가 통화. 금액과 함께 null 또는 판매가 통화와 동일")
    private CurrencyCode listPriceCurrency;

    public void requireIdentity(ProductCurrencyPrice price) {
        if (id != price.id() || productId != price.productId() || salePriceCurrency != price.salePrice().currency()) {
            throw new IllegalArgumentException("상품 가격 식별자·소속·통화는 변경할 수 없습니다");
        }
    }

    public void replaceAmounts(ProductCurrencyPrice price) {
        requireIdentity(price);
        salePriceAmount = price.salePrice().amount();
        listPriceAmount = price.listPrice() == null ? null : price.listPrice().amount();
        listPriceCurrency = price.listPrice() == null ? null : price.listPrice().currency();
    }

    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
