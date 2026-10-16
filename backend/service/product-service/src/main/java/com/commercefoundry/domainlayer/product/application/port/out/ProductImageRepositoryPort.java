package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import java.util.Collection;
import java.util.List;

public interface ProductImageRepositoryPort {

    /** 대표 · 갤러리 모두. 노출 순서 → 아이디 오름차순 — 유형별로 나누는 것은 호출한 쪽이 한다. */
    List<ProductImage> findAllByProductId(long productId);

    /** 상품들의 대표 이미지 (상품당 최대 1건). 빈 컬렉션이면 쿼리 없이 빈 목록이다. */
    List<ProductImage> findThumbnailsByProductIds(Collection<Long> productIds);
}
