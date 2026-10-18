package com.commercefoundry.domainlayer.product.application.mapper;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.domain.model.HsCode;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.shared.commerce.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Entity → Domain 매퍼 중 금액 · 조합 키처럼 손으로 쓴 변환이 섞인 것만 본다. 나머지 필드 대응은 MapStruct 가 컴파일 시점에
 * {@code unmappedTargetPolicy = ERROR} 로 막는다.
 */
class ProductPersistenceMapperTest {

    private final ProductMapper productMapper = new ProductMapperImpl();
    private final SkuMapper skuMapper = new SkuMapperImpl();

    @Test
    @DisplayName("상품: 금액 · 통화 컬럼 쌍을 Money 로 묶는다")
    void productMoney() {
        Product product = productMapper.toDomain(product().listPriceAmount(45_000L).listPriceCurrency(KRW).build());

        assertThat(product.id()).isEqualTo(101L);
        assertThat(product.categoryId()).isEqualTo(10L);
        assertThat(product.salePrice()).isEqualTo(Money.of(39_000, KRW));
        assertThat(product.listPrice()).isEqualTo(Money.of(45_000, KRW));
        assertThat(product.maxQuantityPerOrder()).isNull();
        assertThat(product.customOrder()).isTrue();
    }

    @Test
    @DisplayName("상품: 정가 금액 · 통화가 모두 없으면 정가는 null 이다")
    void productWithoutListPrice() {
        assertThat(productMapper.toDomain(product().build()).listPrice()).isNull();
    }

    @Test
    @DisplayName("상품: 정가 금액과 통화 중 한쪽만 있으면 데이터 오류로 실패한다")
    void productHalfListPrice() {
        assertThatThrownBy(() -> productMapper.toDomain(product().listPriceAmount(45_000L).build())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> productMapper.toDomain(product().listPriceCurrency(KRW).build())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("SKU: 정규형 조합 키를 읽고, 추가금을 Money 로 묶는다")
    void sku() {
        Sku sku = skuMapper.toDomain(sku("5,23"));

        assertThat(sku.optionKey().value()).isEqualTo("5,23");
        assertThat(sku.optionKey().valueIds()).containsExactly(5L, 23L);
        assertThat(sku.additionalPrice()).isEqualTo(Money.of(3_000, KRW));
        assertThat(sku.saleStatus()).isEqualTo(SaleStatus.ON_SALE);
    }

    @Test
    @DisplayName("SKU: 옵션 없는 상품의 빈 조합 키는 빈 키다")
    void skuWithoutOptions() {
        assertThat(skuMapper.toDomain(sku("")).optionKey().isEmpty()).isTrue();
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"23,5,5", "23,5", "5,5"})
    @DisplayName("SKU: 저장된 조합 키가 정규형(오름차순 · 중복 없음)이 아니면 고쳐 읽지 않고 실패한다")
    void skuWithNonCanonicalKey(String stored) {
        assertThatThrownBy(() -> skuMapper.toDomain(sku(stored)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(stored);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"1,,2", "05", "+5", "5,"})
    @DisplayName("SKU: 저장된 조합 키의 형식이 틀리면 실패한다")
    void skuWithMalformedKey(String stored) {
        assertThatThrownBy(() -> skuMapper.toDomain(sku(stored)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(stored);
    }

    @Test
    @DisplayName("SKU: 무게 · HS 코드가 없으면(미입력) null 이다")
    void skuWithoutWeightAndHsCode() {
        Sku sku = skuMapper.toDomain(sku("5,23"));

        assertThat(sku.weightGrams()).isNull();
        assertThat(sku.hsCode()).isNull();
    }

    @Test
    @DisplayName("SKU: 무게는 그대로, HS 코드는 HsCode 로 읽는다")
    void skuWithWeightAndHsCode() {
        Sku sku = skuMapper.toDomain(skuBuilder("").weightGrams(450).hsCode("6211420000").build());

        assertThat(sku.weightGrams()).isEqualTo(450);
        assertThat(sku.hsCode()).isEqualTo(new HsCode("6211420000"));
    }

    @Test
    @DisplayName("SKU: 저장된 HS 코드가 숫자가 아니면(DB check 를 비켜 들어온 데이터) 고쳐 읽지 않고 실패한다")
    void skuWithMalformedHsCode() {
        assertThatThrownBy(() -> skuMapper.toDomain(skuBuilder("").hsCode("6211.42").build()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("6211.42");
    }

    @ParameterizedTest(name = "{0}g")
    @ValueSource(ints = {0, -1})
    @DisplayName("SKU: 저장된 무게가 1g 미만이면 저장 데이터 오류(IllegalStateException)로 실패하고 메시지에 저장값을 담는다")
    void skuWithNonPositiveWeight(int weightGrams) {
        assertThatThrownBy(() -> skuMapper.toDomain(skuBuilder("").weightGrams(weightGrams).build()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("저장된 SKU 무게")
            .hasMessageContaining(String.valueOf(weightGrams));
    }

    private static ProductEntity.ProductEntityBuilder product() {
        return ProductEntity.builder()
            .id(101L)
            .categoryId(10L)
            .name("린넨 생활한복")
            .saleStatus(SaleStatus.ON_SALE)
            .visible(true)
            .salePriceAmount(39_000L)
            .salePriceCurrency(KRW)
            .customOrder(true);
    }

    private static SkuEntity sku(String optionValueKey) {
        return skuBuilder(optionValueKey).build();
    }

    private static SkuEntity.SkuEntityBuilder skuBuilder(String optionValueKey) {
        return SkuEntity.builder()
            .id(301L)
            .productId(101L)
            .optionValueKey(optionValueKey)
            .additionalPriceAmount(3_000L)
            .additionalPriceCurrency(KRW)
            .saleStatus(SaleStatus.ON_SALE);
    }
}
