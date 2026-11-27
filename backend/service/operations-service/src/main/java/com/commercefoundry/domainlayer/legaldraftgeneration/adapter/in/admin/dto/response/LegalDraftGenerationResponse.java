package com.commercefoundry.domainlayer.legaldraftgeneration.adapter.in.admin.dto.response;

import com.commercefoundry.common.dto.metadata.CodeNameDescriptionMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record LegalDraftGenerationResponse(
    @Schema(description = "작업 식별자", example = "1024")
    String jobId,

    @Schema(description = "작업 상태(QUEUED · RUNNING · SUCCEEDED · FAILED · CANCELLED). 작업 어드민 응답과 같은 이름 · 설명",
        example = "{\"code\":\"SUCCEEDED\",\"name\":\"성공\",\"description\":\"실행을 마쳤습니다.\"}")
    CodeNameDescriptionMetadata status,

    @Schema(description = "실패 · 취소 요청 · 시작 템플릿 변경 안내. 없으면 null")
    String notice,

    @Schema(description = "검토용 후보. 성공했고 같은 버전의 시작 템플릿으로 다시 만들 수 있을 때만 있다")
    Candidate candidate,

    @Schema(description = "최초 검토 확인 시각. 없으면 null")
    Instant reviewedAt
) {
    public record Accepted(
        @Schema(description = "작업 식별자", example = "1024")
        String jobId
    ) {}

    public record Candidate(
        @Schema(description = "문서 유형", example = "{\"code\":\"PRIVACY\",\"name\":\"개인정보처리방침\",\"description\":\"개인정보처리방침 검토용 후보입니다.\"}")
        CodeNameDescriptionMetadata type,

        @Schema(description = "문서 언어", example = "ko")
        String locale,

        @Schema(description = "제목")
        String title,

        @Schema(description = "검토용 본문")
        String body,

        @Schema(description = "시작 템플릿 식별자", example = "legal-privacy-ko")
        String templateId,

        @Schema(description = "시작 템플릿 버전", example = "1")
        int templateVersion,

        @Schema(description = "확인되지 않아 빈칸으로 남은 항목")
        List<String> unresolvedItems,

        @Schema(description = "검토 체크리스트")
        List<String> reviewChecklist
    ) {
        @Override
        public String toString() {
            return "Candidate[templateId=" + templateId + ", templateVersion=" + templateVersion + "]";
        }
    }
}
