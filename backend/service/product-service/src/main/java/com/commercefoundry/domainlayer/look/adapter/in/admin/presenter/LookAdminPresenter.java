package com.commercefoundry.domainlayer.look.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.item.LookImageAdminItem;
import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.item.LookProductAdminItem;
import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.response.LookAdminListResponse;
import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.response.LookAdminResponse;
import com.commercefoundry.domainlayer.look.application.info.LookAdminInfo;
import com.commercefoundry.domainlayer.look.application.info.LookProductAdminInfo;
import com.commercefoundry.domainlayer.look.domain.model.LookImage;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 어드민 룩 응답. 식별자 {@code long → String}, 판매 상태 → metadata 변환만 한다. 노출 순서 번호는 내지 않는다(배열 순서로 전한다).
 */
@Component
public class LookAdminPresenter {

    public LookAdminListResponse toListResponse(List<LookAdminInfo> infos) {
        return LookAdminListResponse.builder()
            .looks(infos.stream().map(this::toResponse).toList())
            .build();
    }

    public LookAdminResponse toResponse(LookAdminInfo info) {
        return LookAdminResponse.builder()
            .lookId(String.valueOf(info.lookId()))
            .title(info.title())
            .description(info.description())
            .image(toImageItem(info.image()))
            .visible(info.visible())
            .products(info.products().stream().map(this::toProductItem).toList())
            .build();
    }

    private LookImageAdminItem toImageItem(LookImage image) {
        return LookImageAdminItem.builder()
            .url(image.url())
            .width(image.width())
            .height(image.height())
            .altText(image.altText())
            .build();
    }

    private LookProductAdminItem toProductItem(LookProductAdminInfo product) {
        return LookProductAdminItem.builder()
            .productId(String.valueOf(product.productId()))
            .name(product.name())
            .visible(product.visible())
            .saleStatus(product.saleStatus() == null ? null : product.saleStatus().toMetadata())
            .build();
    }
}
