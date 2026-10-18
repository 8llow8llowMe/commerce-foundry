package com.commercefoundry.domainlayer.product.adapter.in.internal.presenter;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item.SkuInternalItem;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.item.SkuOptionValueInternalItem;
import com.commercefoundry.domainlayer.product.adapter.in.internal.dto.response.SkuInternalResponse;
import com.commercefoundry.domainlayer.product.application.info.SkuInternalInfo;
import com.commercefoundry.domainlayer.product.application.info.SkuOptionValueInfo;
import com.commercefoundry.domainlayer.product.domain.model.HsCode;
import com.commercefoundry.domainlayer.product.domain.model.SaleAvailability;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkuInternalPresenterTest {

    private final SkuInternalPresenter presenter = new SkuInternalPresenter();

    @Test
    @DisplayName("아이디는 문자열, 금액은 MoneyItem, 판매 상태는 metadata 이고 순서 · null 은 그대로다")
    void toResponse() {
        SkuInternalInfo info = SkuInternalInfo.builder()
            .skuId(4611686018427387904L)
            .productId(7301L)
            .productName("저고리")
            .optionValues(List.of(new SkuOptionValueInfo("색상", "아이보리"), new SkuOptionValueInfo("사이즈", "L")))
            .optionName("아이보리 / L")
            .salePrice(Money.of(44_000, KRW))
            .additionalPrice(Money.of(5_000, KRW))
            .saleStatus(SaleAvailability.ON_SALE)
            .purchasable(true)
            .maxQuantityPerOrder(null)
            .customOrder(false)
            .weightGrams(450)
            .hsCode(new HsCode("6211420000"))
            .build();

        SkuInternalResponse response = presenter.toResponse(List.of(info));

        assertThat(response.skus()).containsExactly(SkuInternalItem.builder()
            .skuId("4611686018427387904")
            .productId("7301")
            .productName("저고리")
            .optionValues(List.of(
                SkuOptionValueInternalItem.builder().optionGroupName("색상").optionValueName("아이보리").build(),
                SkuOptionValueInternalItem.builder().optionGroupName("사이즈").optionValueName("L").build()
            ))
            .optionName("아이보리 / L")
            .salePrice(MoneyItem.of(44_000, "KRW"))
            .additionalPrice(MoneyItem.of(5_000, "KRW"))
            .saleStatus(CodeNameDescriptionMetadata.of("ON_SALE", "판매중", "구매할 수 있습니다."))
            .purchasable(true)
            .maxQuantityPerOrder(null)
            .customOrder(false)
            .weightGrams(450)
            .hsCode("6211420000")
            .build());
    }

    @Test
    @DisplayName("무게 · HS 코드가 미입력이면 null 그대로다 — 기본값으로 채우지 않는다")
    void missingWeightAndHsCode() {
        SkuInternalInfo info = SkuInternalInfo.builder()
            .skuId(1L)
            .productId(7301L)
            .productName("저고리")
            .optionValues(List.of())
            .optionName("")
            .salePrice(Money.of(39_000, KRW))
            .additionalPrice(Money.of(0, KRW))
            .saleStatus(SaleAvailability.ON_SALE)
            .purchasable(true)
            .customOrder(false)
            .build();

        SkuInternalItem item = presenter.toResponse(List.of(info)).skus().getFirst();

        assertThat(item.weightGrams()).isNull();
        assertThat(item.hsCode()).isNull();
    }

    @Test
    @DisplayName("빈 목록은 skus: [] 다")
    void empty() {
        assertThat(presenter.toResponse(List.of()).skus()).isEmpty();
    }
}
