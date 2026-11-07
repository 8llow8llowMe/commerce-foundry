package com.commercefoundry.domainlayer.product.application.service.processor;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.category.domain.model.Category;
import com.commercefoundry.domainlayer.category.domain.model.CategoryTree;
import com.commercefoundry.domainlayer.product.application.command.ProductCreateCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCategoryRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductOptionRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.SkuRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockAdjustmentHistoryRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.StockRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.OptionGroup;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceSnapshot;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceWritePlan;
import com.commercefoundry.domainlayer.product.domain.model.OptionValue;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.domainlayer.product.domain.model.SkuOptionKey;
import com.commercefoundry.domainlayer.product.domain.model.Stock;
import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentHistory;
import com.commercefoundry.domainlayer.product.domain.model.StockAdjustmentReason;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 상품 등록 판정(옵션 · SKU 구성 · 가격 · 카테고리)과 저장(옵션 아이디로 조합 키 · 처음 재고 + 입고 이력). */
class ProductWriteProcessorTest {

    private final ProductRepositoryPort productPort = mock(ProductRepositoryPort.class);
    private final ProductCategoryRepositoryPort categoryPort = mock(ProductCategoryRepositoryPort.class);
    private final ProductOptionRepositoryPort optionPort = mock(ProductOptionRepositoryPort.class);
    private final SkuRepositoryPort skuPort = mock(SkuRepositoryPort.class);
    private final StockRepositoryPort stockPort = mock(StockRepositoryPort.class);
    private final StockAdjustmentHistoryRepositoryPort historyPort = mock(StockAdjustmentHistoryRepositoryPort.class);
    private final LegacyPriceWriteProcessor legacyPrices = mock(LegacyPriceWriteProcessor.class);
    private final ProductWriteProcessor processor = new ProductWriteProcessor(
        productPort, categoryPort, optionPort, skuPort, stockPort, historyPort, new SnowflakeIdGenerator(1, 1), legacyPrices
    );

    @BeforeEach
    void prepareRealPricePlan() {
        var ids = new AtomicLong(100000);
        when(legacyPrices.prepareNew(any())).thenAnswer(call -> LegacyPriceWritePlan.prepareNew(call.getArgument(0), List.of(), List.of(), ids::incrementAndGet));
    }

    private static ProductCreateCommand.OptionGroup group(String name, String... values) {
        return new ProductCreateCommand.OptionGroup(name, List.of(values));
    }

    private static ProductCreateCommand.Sku sku(long additional, SaleStatus status, String... values) {
        return ProductCreateCommand.Sku.builder().optionValues(List.of(values)).additionalPrice(Money.of(additional, KRW)).saleStatus(status).build();
    }

    private static ProductCreateCommand.ProductCreateCommandBuilder base() {
        return ProductCreateCommand.builder()
            .name("저고리").categoryId(12L).categoryIds(List.of()).saleStatus(SaleStatus.ON_SALE).visible(false)
            .salePrice(Money.of(59_000, KRW)).optionGroups(List.of()).skus(List.of(sku(0, SaleStatus.ON_SALE))).createdByMemberId(100L);
    }

    @Nested
    @DisplayName("판정")
    class Validate {

        @Test
        @DisplayName("옵션 없는 상품은 SKU 한 개, 옵션이 있으면 SKU 마다 그룹 순서대로 값 하나 — 아니면 PRODUCT_013")
        void combinations() {
            processor.validate(base().build());
            processor.validate(base().optionGroups(List.of(group("색상", "빨강", "파랑"), group("사이즈", "S", "M")))
                .skus(List.of(sku(0, SaleStatus.ON_SALE, "빨강", "S"), sku(1000, SaleStatus.ON_SALE, "파랑", "M"))).build());

            assertCode(base().skus(List.of(sku(0, SaleStatus.ON_SALE), sku(0, SaleStatus.ON_SALE))).build(), ProductErrorCode.PRODUCT_OPTION_COMBINATION_INVALID);
            assertCode(base().optionGroups(List.of(group("색상", "빨강"), group("사이즈", "S"))).skus(List.of(sku(0, SaleStatus.ON_SALE, "빨강"))).build(),
                ProductErrorCode.PRODUCT_OPTION_COMBINATION_INVALID);
            assertCode(base().optionGroups(List.of(group("색상", "빨강"))).skus(List.of(sku(0, SaleStatus.ON_SALE, "초록"))).build(),
                ProductErrorCode.PRODUCT_OPTION_COMBINATION_INVALID);
            assertCode(base().optionGroups(List.of(group("색상", "빨강"), group("색상", "파랑"))).build(), ProductErrorCode.PRODUCT_OPTION_COMBINATION_INVALID);
            assertCode(base().optionGroups(List.of(group("색상", "빨강", "빨강"))).build(), ProductErrorCode.PRODUCT_OPTION_COMBINATION_INVALID);
        }

        @Test
        @DisplayName("같은 조합 SKU 두 개는 PRODUCT_014")
        void duplicateCombination() {
            assertCode(base().optionGroups(List.of(group("색상", "빨강", "파랑")))
                .skus(List.of(sku(0, SaleStatus.ON_SALE, "빨강"), sku(0, SaleStatus.STOP_SELLING, "빨강"))).build(), ProductErrorCode.PRODUCT_SKU_COMBINATION_DUPLICATED);
        }

        @Test
        @DisplayName("가격 — 정가 · 추가금 통화는 판매가와 같고 정가는 판매가보다 높으며, 판매중 SKU 중 하나는 추가금 0 — 아니면 PRODUCT_016. 판매중 SKU 가 없으면 따지지 않는다")
        void pricing() {
            assertCode(base().listPrice(Money.of(10, USD)).build(), ProductErrorCode.PRODUCT_PRICE_RULE_VIOLATED);
            assertCode(base().listPrice(Money.of(59_000, KRW)).build(), ProductErrorCode.PRODUCT_PRICE_RULE_VIOLATED);
            processor.validate(base().listPrice(Money.of(69_000, KRW)).build());
            assertCode(base().skus(List.of(ProductCreateCommand.Sku.builder().optionValues(List.of()).additionalPrice(Money.of(0, USD))
                .saleStatus(SaleStatus.ON_SALE).build())).build(), ProductErrorCode.PRODUCT_PRICE_RULE_VIOLATED);
            assertCode(base().optionGroups(List.of(group("색상", "빨강", "파랑")))
                .skus(List.of(sku(1000, SaleStatus.ON_SALE, "빨강"), sku(0, SaleStatus.STOP_SELLING, "파랑"))).build(), ProductErrorCode.PRODUCT_PRICE_RULE_VIOLATED);

            processor.validate(base().skus(List.of(sku(1000, SaleStatus.STOP_SELLING))).build());
        }

        @Test
        @DisplayName("진열 카테고리 — 대표가 없으면 맨 앞에 더하고 중복은 하나로. 없는 카테고리는 모두 모아 PRODUCT_012")
        void categories() {
            assertThat(processor.displayCategoryIdsOf(base().categoryIds(List.of(30L, 31L, 30L)).build())).containsExactly(12L, 30L, 31L);
            assertThat(processor.displayCategoryIdsOf(base().categoryIds(List.of(30L, 12L)).build())).containsExactly(30L, 12L);

            CategoryTree tree = CategoryTree.of(List.of(new Category(12L, null, "상의", 0, true)));
            processor.requireCategories(tree, List.of(12L));
            assertThatThrownBy(() -> processor.requireCategories(tree, List.of(12L, 30L, 31L)))
                .isInstanceOfSatisfying(ProductException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_CATEGORY_NOT_FOUND);
                    assertThat(exception.getMessage()).contains("30, 31");
                });
        }

        private void assertCode(ProductCreateCommand command, ProductErrorCode code) {
            assertThatThrownBy(() -> processor.validate(command))
                .isInstanceOfSatisfying(ProductException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
        }
    }

    @Nested
    @DisplayName("저장")
    class Create {

        @Test
        @DisplayName("상품 · 진열 카테고리 · 옵션 · SKU 를 저장한다 — SKU 조합 키는 저장한 값 아이디이고, 맞춤 제작은 꺼짐 · 버전은 비어 있고 등록자가 남는다")
        @SuppressWarnings("unchecked")
        void storesProduct() {
            ProductCreateCommand command = base()
                .optionGroups(List.of(group("색상", "빨강", "파랑"), group("사이즈", "S")))
                .skus(List.of(sku(0, SaleStatus.ON_SALE, "파랑", "S")))
                .build();

            long productId = processor.create(command, "<p>정제본</p>", List.of(12L, 30L));

            ArgumentCaptor<Product> product = ArgumentCaptor.forClass(Product.class);
            verify(productPort).insert(product.capture());
            assertThat(product.getValue().id()).isEqualTo(productId);
            assertThat(product.getValue().descriptionHtml()).isEqualTo("<p>정제본</p>");
            assertThat(product.getValue().customOrder()).isFalse();
            assertThat(product.getValue().version()).isNull();
            assertThat(product.getValue().createdByMemberId()).isEqualTo(100L);
            verify(categoryPort).insertLinks(eq(productId), eq(List.of(12L, 30L)), any());

            ArgumentCaptor<List<OptionGroup>> groups = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<List<OptionValue>> values = ArgumentCaptor.forClass(List.class);
            verify(optionPort).insertAll(groups.capture(), values.capture());
            assertThat(groups.getValue()).allSatisfy(g -> assertThat(g.nameEn()).isNull());
            assertThat(values.getValue()).allSatisfy(v -> assertThat(v.nameEn()).isNull());
            assertThat(groups.getValue()).extracting(OptionGroup::name).containsExactly("색상", "사이즈");
            assertThat(groups.getValue()).extracting(OptionGroup::displayOrder).containsExactly(0, 1);
            Map<String, Long> valueIds = values.getValue().stream().collect(Collectors.toMap(OptionValue::name, OptionValue::id, (a, b) -> a));

            ArgumentCaptor<List<Sku>> skus = ArgumentCaptor.forClass(List.class);
            verify(skuPort).insertAll(skus.capture());
            assertThat(skus.getValue().getFirst().optionKey()).isEqualTo(SkuOptionKey.of(List.of(valueIds.get("파랑"), valueIds.get("S"))));
            var snapshot = ArgumentCaptor.forClass(LegacyPriceSnapshot.class);
            verify(legacyPrices).prepareNew(snapshot.capture());
            assertThat(snapshot.getValue()).isEqualTo(LegacyPriceSnapshot.of(product.getValue(), skus.getValue()));
            verify(stockPort, never()).create(any());
            verify(historyPort, never()).append(any());
        }

        @Test
        @DisplayName("다른 그룹에 같은 값 이름이 있어도 그룹 순서로 구분해 그 그룹의 값 아이디를 고른다")
        @SuppressWarnings("unchecked")
        void sameValueNameAcrossGroups() {
            ProductCreateCommand command = base()
                .optionGroups(List.of(group("상의", "1", "2"), group("하의", "1", "2")))
                .skus(List.of(sku(0, SaleStatus.ON_SALE, "2", "1")))
                .build();

            processor.create(command, null, List.of(12L));

            ArgumentCaptor<List<OptionGroup>> groups = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<List<OptionValue>> values = ArgumentCaptor.forClass(List.class);
            verify(optionPort).insertAll(groups.capture(), values.capture());
            long top = groups.getValue().get(0).id();
            long bottom = groups.getValue().get(1).id();
            long top2 = values.getValue().stream().filter(value -> value.optionGroupId() == top && value.name().equals("2")).findFirst().orElseThrow().id();
            long bottom1 = values.getValue().stream().filter(value -> value.optionGroupId() == bottom && value.name().equals("1")).findFirst().orElseThrow().id();
            ArgumentCaptor<List<Sku>> skus = ArgumentCaptor.forClass(List.class);
            verify(skuPort).insertAll(skus.capture());
            assertThat(skus.getValue().getFirst().optionKey()).isEqualTo(SkuOptionKey.of(List.of(top2, bottom1)));
        }

        @Test
        @DisplayName("처음 재고를 준 SKU 만 재고 행과 입고(RESTOCK) 이력 1건이 생긴다 — 이력은 빈 재고에서 시작하고 등록한 어드민이 남는다")
        void initialStock() {
            ProductCreateCommand command = base()
                .optionGroups(List.of(group("사이즈", "S", "M")))
                .skus(List.of(
                    ProductCreateCommand.Sku.builder().optionValues(List.of("S")).additionalPrice(Money.of(0, KRW)).saleStatus(SaleStatus.ON_SALE)
                        .hsCode("6211421000").weightGrams(350).initialStock(new ProductCreateCommand.InitialStock(true, 30)).build(),
                    sku(0, SaleStatus.ON_SALE, "M")
                ))
                .build();

            processor.create(command, null, List.of(12L));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Sku>> skus = ArgumentCaptor.forClass(List.class);
            verify(skuPort).insertAll(skus.capture());
            Sku first = skus.getValue().getFirst();
            assertThat(first.hsCode().value()).isEqualTo("6211421000");
            assertThat(first.weightGrams()).isEqualTo(350);

            verify(stockPort).create(new Stock(first.id(), true, 30));
            ArgumentCaptor<StockAdjustmentHistory> history = ArgumentCaptor.forClass(StockAdjustmentHistory.class);
            verify(historyPort).append(history.capture());
            assertThat(history.getValue().skuId()).isEqualTo(first.id());
            assertThat(history.getValue().beforeQuantity()).isZero();
            assertThat(history.getValue().afterQuantity()).isEqualTo(30);
            assertThat(history.getValue().reason()).isEqualTo(StockAdjustmentReason.RESTOCK);
            assertThat(history.getValue().memo()).isEqualTo(ProductWriteProcessor.INITIAL_STOCK_MEMO);
            assertThat(history.getValue().adminMemberId()).isEqualTo(100L);
            assertThat(skus.getValue()).extracting(Sku::id).hasSize(2).doesNotHaveDuplicates();

            var order = inOrder(skuPort, productPort, legacyPrices, stockPort, historyPort);
            order.verify(skuPort).insertAll(any());
            order.verify(productPort).flushNewProduct();
            order.verify(legacyPrices).prepareNew(any());
            order.verify(legacyPrices).write(any(LegacyPriceWritePlan.class));
            order.verify(stockPort).create(any());
            order.verify(historyPort).append(any());
        }
    }
}
