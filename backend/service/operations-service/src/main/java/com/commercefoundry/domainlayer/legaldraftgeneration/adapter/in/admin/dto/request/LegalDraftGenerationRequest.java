package com.commercefoundry.domainlayer.legaldraftgeneration.adapter.in.admin.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

public record LegalDraftGenerationRequest(
    @Schema(allowableValues = {"TERMS", "PRIVACY"}) String type,
    @Schema(allowableValues = {"ko", "en"}) String locale,
    @Schema(description = "시작 템플릿 조회에서 받은 버전") int templateVersion,
    @Schema(description = "시작 템플릿 빈칸 식별자와 확인된 공개용 운영 사실. 항목당 600자 이하") Map<String, String> confirmedFacts
) {
    @Override
    public String toString() {
        return "LegalDraftGenerationRequest";
    }
}
