package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.port.out.ProductImageRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 같은 서비스의 다른 컨텍스트(사이즈표 초안 이미지 후보)가 상품 정지 이미지를 읽는 용도별 조회. <b>숨긴 상품도 담는다 — 어드민 전용이다.</b>
 * 상품 존재 판정은 {@link ProductLookupProcessor} 로 먼저 한다. 영상은 담지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ProductImageLookupProcessor {

    private final ProductImageRepositoryPort productImageRepositoryPort;

    /** 대표 · 갤러리 이미지를 노출 순서 → 아이디 오름차순으로 한 번에 읽는다. 호출한 쪽에 트랜잭션이 없어도 짧은 읽기 트랜잭션으로 읽는다. */
    @Transactional(readOnly = true)
    public List<ProductImage> findStillImagesIncludingHidden(long productId) {
        return findStillImagesIncludingHidden(List.of(productId));
    }

    /** 여러 상품의 대표 · 갤러리 이미지를 한 번에 읽는다(상품마다 따로 조회하지 않는다). 빈 목록이면 조회하지 않는다. 정렬 · 트랜잭션은 단건과 같다. */
    @Transactional(readOnly = true)
    public List<ProductImage> findStillImagesIncludingHidden(List<Long> productIds) {
        return productIds.isEmpty() ? List.of() : productImageRepositoryPort.findStillImagesByProductIds(productIds);
    }
}
