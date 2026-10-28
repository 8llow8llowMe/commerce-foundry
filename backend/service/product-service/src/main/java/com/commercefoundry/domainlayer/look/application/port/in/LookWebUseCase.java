package com.commercefoundry.domainlayer.look.application.port.in;

import com.commercefoundry.domainlayer.look.adapter.in.web.dto.response.LookDetailResponse;
import com.commercefoundry.domainlayer.look.adapter.in.web.dto.response.LookSummaryResponse;
import com.commercefoundry.domainlayer.look.adapter.in.web.dto.response.ProductLookListResponse;
import com.commercefoundry.persistence.dto.SliceResponse;

/**
 * 고객 룩 조회 (로그인 불필요). 기능 플래그({@code store.features.looks})가 꺼지면 목록은 빈 쪽, 상세는 404 {@code LOOK_001}, 상품별 룩은 빈 목록이다.
 */
public interface LookWebUseCase {

    /** 보이는 룩 한 쪽 — 노출 순서 → 아이디 오름차순. */
    SliceResponse<LookSummaryResponse> getLooks(int page, int size);

    /** 없거나 숨긴 룩은 404 {@code LOOK_001} 이다. */
    LookDetailResponse getLook(long lookId);

    /** 상품이 든 보이는 룩. 없거나 숨긴 상품은 플래그와 무관하게 먼저 404 {@code PRODUCT_001} 이다. */
    ProductLookListResponse getLooksOfProduct(long productId);
}
