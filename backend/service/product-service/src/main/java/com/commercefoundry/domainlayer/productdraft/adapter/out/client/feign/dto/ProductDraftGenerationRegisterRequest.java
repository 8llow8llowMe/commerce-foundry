package com.commercefoundry.domainlayer.productdraft.adapter.out.client.feign.dto;

import java.time.Instant;

/**
 * 생성 작업 등록 요청 — operations 영수증의 대조 칸만 보낸다. 같은 (초안, 회차)의 재시도는 모든 칸이 저장 값 그대로여야 재생된다.
 *
 * @param expiresAt 초안 만료 시각(마이크로초로 자른 저장 값)
 */
public record ProductDraftGenerationRegisterRequest(long draftId, int generationNo, String inputHash, long actorMemberId, Instant expiresAt) {
}
