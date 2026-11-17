package com.commercefoundry.domainlayer.productdraftgeneration.application.command;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** product 가 초안을 커밋한 뒤 보내는 등록 요청. 회차는 영수증에만 두고 작업 payload 에는 싣지 않는다 (#359 PR-2b-2 결정 1). */
public record ProductDraftGenerationRegisterCommand(long draftId, int generationNo, String inputHash, long actorMemberId, Instant expiresAt) {
    public ProductDraftGenerationRegisterCommand {
        // product 저장값 · timestamp(6) 와 같은 정밀도로 맞춰야 원래 요청과 다시 읽은 요청을 같은 것으로 본다.
        expiresAt = expiresAt == null ? null : expiresAt.truncatedTo(ChronoUnit.MICROS);
    }
}
