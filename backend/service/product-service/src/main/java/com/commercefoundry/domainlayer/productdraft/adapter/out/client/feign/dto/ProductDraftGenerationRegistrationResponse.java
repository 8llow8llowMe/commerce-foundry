package com.commercefoundry.domainlayer.productdraft.adapter.out.client.feign.dto;

import tools.jackson.databind.annotation.JsonDeserialize;

/** 생성 작업 등록 응답 dataBody. 아이디 · hash 는 문자열 토큰만 받는다 — 숫자 JSON 을 문자열로 바꿔 받지 않는다. 회차가 없으면 null 이고 계약 위반이다. */
public record ProductDraftGenerationRegistrationResponse(
    @JsonDeserialize(using = StrictStringDeserializer.class) String draftId,
    @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer generationNo,
    @JsonDeserialize(using = StrictStringDeserializer.class) String jobId,
    @JsonDeserialize(using = StrictStringDeserializer.class) String inputHash
) {
}
