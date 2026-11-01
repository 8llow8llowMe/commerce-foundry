package com.commercefoundry.domainlayer.productdraft.application.command;

import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftFacts;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImage;
import java.util.List;
import lombok.Builder;

/**
 * 초안 생성 — 운영자 입력과 만든 어드민. 이미지 주소 · 대표 이미지 수 · 중복은 Processor 가 본다.
 *
 * @param requestedByMemberId access 토큰의 memberId (본문으로 받지 않는다)
 * @param images 배열 순서 = 화면 순서
 */
@Builder
public record ProductDraftCreateCommand(long requestedByMemberId, ProductDraftFacts facts, List<ProductDraftImage> images) {
}
