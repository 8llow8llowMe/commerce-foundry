package com.commercefoundry.domainlayer.look.adapter.in.web.presenter;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.look.adapter.in.web.dto.item.LookImageItem;
import com.commercefoundry.domainlayer.look.adapter.in.web.dto.item.LookProductItem;
import com.commercefoundry.domainlayer.look.adapter.in.web.dto.item.LookProductThumbnailItem;
import com.commercefoundry.domainlayer.look.adapter.in.web.dto.response.LookDetailResponse;
import com.commercefoundry.domainlayer.look.adapter.in.web.dto.response.LookSummaryResponse;
import com.commercefoundry.domainlayer.look.adapter.in.web.dto.response.ProductLookListResponse;
import com.commercefoundry.domainlayer.look.application.info.LookInfo;
import com.commercefoundry.domainlayer.look.application.info.LookProductInfo;
import com.commercefoundry.domainlayer.look.domain.model.LookImage;
import com.commercefoundry.persistence.dto.SliceResponse;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import org.springframework.data.domain.Slice;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 고객 룩 응답. 식별자 {@code long → String}, 금액 {@code Money → MoneyItem}, 판매 상태 → metadata 변환만 한다.
 * 상품 카드는 상품 목록 Presenter({@code ProductWebPresenter#toListItem})와 같은 규칙으로 옮긴다 — 다른 컨텍스트 Presenter 는 쓰지 않는다
 * ({@code LookProductCardParityTest} 가 두 JSON 을 비교한다).
 */
@Component
public class LookWebPresenter {

    public SliceResponse<LookSummaryResponse> toSliceResponse(Slice<LookInfo> slice) {
        return toSliceResponse(slice, Locale.KOREAN);
    }

    /** @param locale 상품 판매 상태의 표시 언어 (#20). 상품 이름은 상품 카드가 이미 그 언어로 골라 왔다 */
    public SliceResponse<LookSummaryResponse> toSliceResponse(Slice<LookInfo> slice, Locale locale) {
        return SliceResponse.of(slice).map(info -> toSummary(info, locale));
    }

    public LookDetailResponse toDetailResponse(LookInfo info) {
        return toDetailResponse(info, Locale.KOREAN);
    }

    public LookDetailResponse toDetailResponse(LookInfo info, Locale locale) {
        return LookDetailResponse.builder()
            .lookId(String.valueOf(info.lookId()))
            .title(info.title())
            .description(info.description())
            .image(toImageItem(info.image()))
            .products(info.products().stream().map(product -> toProductItem(product, locale)).toList())
            .build();
    }

    public ProductLookListResponse toProductLookListResponse(List<LookInfo> infos) {
        return toProductLookListResponse(infos, Locale.KOREAN);
    }

    public ProductLookListResponse toProductLookListResponse(List<LookInfo> infos, Locale locale) {
        return ProductLookListResponse.builder()
            .looks(infos.stream().map(info -> toSummary(info, locale)).toList())
            .build();
    }

    private LookSummaryResponse toSummary(LookInfo info, Locale locale) {
        return LookSummaryResponse.builder()
            .lookId(String.valueOf(info.lookId()))
            .title(info.title())
            .image(toImageItem(info.image()))
            .products(info.products().stream().map(product -> toProductItem(product, locale)).toList())
            .build();
    }

    private LookImageItem toImageItem(LookImage image) {
        return LookImageItem.builder()
            .url(image.url())
            .width(image.width())
            .height(image.height())
            .altText(image.altText())
            .build();
    }

    private LookProductItem toProductItem(LookProductInfo product, Locale locale) {
        return LookProductItem.builder()
            .productId(String.valueOf(product.productId()))
            .name(product.name())
            .thumbnail(toThumbnailItem(product.thumbnail()))
            .hoverImage(toThumbnailItem(product.hoverImage()))
            .salePrice(toMoneyItem(product.salePrice()))
            .listPrice(toMoneyItem(product.listPrice()))
            .discountRate(product.discountRate())
            .hasOptionSurcharge(product.hasOptionSurcharge())
            .saleStatus(product.saleStatus().toMetadata(locale))
            .customOrder(product.customOrder())
            .preorder(product.preorder())
            .build();
    }

    private LookProductThumbnailItem toThumbnailItem(LookProductInfo.Thumbnail thumbnail) {
        if (thumbnail == null) {
            return null;
        }
        return LookProductThumbnailItem.builder()
            .imageId(String.valueOf(thumbnail.imageId()))
            .url(thumbnail.url())
            .width(thumbnail.width())
            .height(thumbnail.height())
            .altText(thumbnail.altText())
            .build();
    }

    /** null 이면 null 이다 (정가가 없는 상품). 상품 컨텍스트의 {@code MoneyItems} 와 같은 변환이다. */
    private static MoneyItem toMoneyItem(Money money) {
        return money == null ? null : MoneyItem.of(money.amount(), money.currency().name());
    }
}
