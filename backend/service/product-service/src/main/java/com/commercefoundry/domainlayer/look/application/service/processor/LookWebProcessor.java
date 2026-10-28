package com.commercefoundry.domainlayer.look.application.service.processor;

import com.commercefoundry.domainlayer.look.application.exception.LookErrorCode;
import com.commercefoundry.domainlayer.look.application.exception.LookException;
import com.commercefoundry.domainlayer.look.application.info.LookInfo;
import com.commercefoundry.domainlayer.look.application.info.LookProductInfo;
import com.commercefoundry.domainlayer.look.application.port.out.LookRepositoryPort;
import com.commercefoundry.domainlayer.look.domain.model.Look;
import com.commercefoundry.domainlayer.look.domain.model.LookContent;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import com.commercefoundry.domainlayer.product.domain.model.ProductSummary;
import com.commercefoundry.global.properties.StoreProperties;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Component;

/**
 * 고객 룩 조회 — 노출 규칙(기능 플래그 · 숨긴 룩)은 여기 한 곳에서 판정한다. 어드민 관리({@link LookProcessor})는 플래그 · 노출과 무관해 섞지 않는다.
 *
 * <ul>
 *   <li><b>기능 플래그</b>({@code store.features.looks}) — 꺼지면 데이터는 그대로 두고 응답에서만 숨긴다: 목록은 빈 쪽, 상세는 404 {@code LOOK_001},
 *       상품별 룩은 빈 목록. 꺼져 있으면 저장소를 읽지 않는다.</li>
 *   <li><b>숨긴 룩</b>({@code visible=false})은 목록에 없고, 상세는 존재를 드러내지 않게 없는 룩과 같은 404 {@code LOOK_001} 이다.</li>
 *   <li><b>읽는 사이 삭제</b> — 룩 행을 읽은 뒤 아이템을 읽기 전에 삭제가 커밋되어 아이템이 비면 목록에서 빼고({@link LookContent#assemble}) 상세는 404 다.</li>
 * </ul>
 * 상품 카드는 이 컨텍스트가 읽지 않는다 — Facade 가 상품 컨텍스트 Processor 로 받은 {@link ProductSummary} 를 인자로 넘긴다 (H8).
 */
@Component
@RequiredArgsConstructor
public class LookWebProcessor {

    private final LookRepositoryPort lookRepositoryPort;
    private final StoreProperties storeProperties;

    /** 보이는 룩 한 쪽과 아이템(룩 1번 · 아이템 1번). 플래그가 꺼지면 쿼리 없이 빈 쪽이다. 읽는 사이 지워진 룩이 빠지면 한 쪽이 size 보다 작을 수 있다. */
    public Slice<LookContent> getVisibleSlice(int page, int size) {
        if (!isExposed()) {
            return new SliceImpl<>(List.of(), PageRequest.of(page, size), false);
        }
        Slice<Look> looks = lookRepositoryPort.findVisibleSlice(page, size);
        return new SliceImpl<>(withItems(looks.getContent()), looks.getPageable(), looks.hasNext());
    }

    /** 보이는 룩 하나. 플래그 꺼짐 · 없는 룩 · 숨긴 룩 · 읽는 사이 삭제는 모두 404 {@code LOOK_001} 이다. */
    public LookContent getVisibleContent(long lookId) {
        if (!isExposed()) {
            throw new LookException(LookErrorCode.NOT_FOUND_LOOK);
        }
        Look look = lookRepositoryPort.findById(lookId)
            .filter(Look::visible)
            .orElseThrow(() -> new LookException(LookErrorCode.NOT_FOUND_LOOK));
        // 읽는 사이 삭제되어 아이템이 비면 assemble 이 룩을 뺀다 — 목록과 같은 규칙(domain 한 곳)으로 404 다
        return LookContent.assemble(List.of(look), lookRepositoryPort.findItemsByLookIds(List.of(lookId))).stream()
            .findFirst()
            .orElseThrow(() -> new LookException(LookErrorCode.NOT_FOUND_LOOK));
    }

    /** 상품이 든 보이는 룩 전체(노출 순서). 플래그가 꺼지면 쿼리 없이 빈 목록이다. 상품의 존재 · 노출은 호출한 쪽이 먼저 확인했다. */
    public List<LookContent> getVisibleContentsOfProduct(long productId) {
        if (!isExposed()) {
            return List.of();
        }
        return withItems(lookRepositoryPort.findVisibleByProductId(productId));
    }

    /**
     * 고객 응답 모양. 고객에게 보이지 않는 상품(숨김 · 없음 — {@code visibleProducts} 에 없다)은 뺀다. 판매 종료 · 품절은 판매 상태로 남는다.
     * <b>상품이 모두 빠져도 룩은 남긴다(빈 목록)</b> — 룩 노출은 어드민이 정한 것이고, 빈 목록일 때 "함께 담기" 를 숨길지는 화면이 정한다(메인 전시 상품 픽과 같다).
     *
     * @param visibleProducts 고객에게 보이는 상품의 카드 (상품 아이디 → 카드)
     */
    public LookInfo toInfo(LookContent content, Map<Long, ProductSummary> visibleProducts) {
        Look look = content.look();
        List<LookProductInfo> products = content.productIds().stream()
            .map(visibleProducts::get)
            .filter(Objects::nonNull)
            .map(LookWebProcessor::toProduct)
            .toList();
        return new LookInfo(look.id(), look.title(), look.description(), look.image(), products);
    }

    public List<LookInfo> toInfos(List<LookContent> contents, Map<Long, ProductSummary> visibleProducts) {
        return contents.stream().map(content -> toInfo(content, visibleProducts)).toList();
    }

    private boolean isExposed() {
        return storeProperties.features().looks();
    }

    private List<LookContent> withItems(List<Look> looks) {
        if (looks.isEmpty()) {
            return List.of();
        }
        return LookContent.assemble(looks, lookRepositoryPort.findItemsByLookIds(looks.stream().map(Look::id).toList()));
    }

    private static LookProductInfo toProduct(ProductSummary summary) {
        return new LookProductInfo(
            summary.productId(), summary.name(), toThumbnail(summary.thumbnail()), toThumbnail(summary.hoverImage()),
            summary.salePrice(), summary.listPrice(), summary.discountRate(), summary.hasOptionSurcharge(),
            summary.saleStatus(), summary.customOrder(), summary.preorder()
        );
    }

    private static LookProductInfo.Thumbnail toThumbnail(ProductImage image) {
        return image == null ? null : new LookProductInfo.Thumbnail(image.id(), image.url(), image.width(), image.height(), image.altText());
    }
}
