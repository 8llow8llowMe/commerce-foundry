package com.commercefoundry.domainlayer.legaldraftgeneration.adapter.in.admin.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

public record LegalDraftGenerationRequest(
    @Schema(description = "문서 유형", allowableValues = {"TERMS", "PRIVACY"})
    String type,

    @Schema(description = "문서 언어", allowableValues = {"ko", "en"})
    String locale,

    @Schema(description = "시작 템플릿 조회에서 받은 버전. 빠지면 400(LEGAL_DRAFT_GENERATION_100)", example = "1")
    Integer templateVersion,

    @Schema(description = "시작 템플릿 빈칸 식별자와 확인된 공개용 운영 사실. 항목당 600자 이하")
    Map<String, String> confirmedFacts
) {
    @Override
    public String toString() {
        return "LegalDraftGenerationRequest";
    }
}
