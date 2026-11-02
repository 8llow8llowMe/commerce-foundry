package com.commercefoundry.domainlayer.job.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "비공개 작업 결과 다운로드")
public record JobResultDownloadResponse(
    @Schema(description = "단기 서명 URL. 로그·분석 도구에 전달하지 않는다") String url,
    @Schema(description = "서명 URL 만료 시각") Instant expiresAt,
    @Schema(description = "서버가 지정한 일반 파일명") String fileName,
    @Schema(description = "결과 MIME") String contentType,
    @Schema(description = "결과 바이트 크기") long sizeBytes
) {
    @Override
    public String toString() { return "JobResultDownloadResponse[expiresAt=" + expiresAt + "]"; }
}
