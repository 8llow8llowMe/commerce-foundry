package com.commercefoundry.domainlayer.productdraft.application.command;

import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftFacts;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImage;
import java.util.List;
import lombok.Builder;

/**
 * 초안 생성 — 운영자 입력과 만든 어드민. 이미지 주소 · 대표 이미지 수 · 중복 · 관리 키 · 저장소 확인은 Processor 가 본다.
 *
 * @param requestedByMemberId access 토큰의 memberId (본문으로 받지 않는다)
 * @param images 배열 순서 = 화면 순서
 */
@Builder
public record ProductDraftCreateCommand(long requestedByMemberId, ProductDraftFacts facts, List<ProductDraftImage> images) {

    /** 원본을 고정한 이미지로 바꾼 사본 — 순서는 호출한 쪽이 지킨다. */
    public ProductDraftCreateCommand withImages(List<ProductDraftImage> pinnedImages) {
        return new ProductDraftCreateCommand(requestedByMemberId, facts, pinnedImages);
    }
}
