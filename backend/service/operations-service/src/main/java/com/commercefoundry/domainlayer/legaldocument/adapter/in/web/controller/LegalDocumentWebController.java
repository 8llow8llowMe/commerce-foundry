package com.commercefoundry.domainlayer.legaldocument.adapter.in.web.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.legaldocument.adapter.in.web.dto.response.LegalDocumentWebResponses;
import com.commercefoundry.domainlayer.legaldocument.application.exception.LegalDocumentValidationMessage;
import com.commercefoundry.domainlayer.legaldocument.application.port.in.LegalDocumentWebUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "법적 문서", description = "실제 게시한 약관·개인정보처리방침만 공개합니다. 언어 fallback은 없습니다.")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/store/legal-documents")
public class LegalDocumentWebController {
    private final LegalDocumentWebUseCase useCase;

    @Operation(summary = "현재 게시 문서 목록")
    @PreAuthorize("permitAll()")
    @GetMapping
    public ResponseEntity<Response<List<LegalDocumentWebResponses.Summary>>> list(
        @Parameter(description = "언어 제어 코드", schema = @Schema(allowableValues = {"ko", "en"})) @RequestParam(defaultValue = "ko") String locale
    ) {
        return ok(useCase.list(locale));
    }

    @Operation(summary = "현재 게시 문서 조회", description = "해당 언어 미게시 문서는 404입니다.")
    @PreAuthorize("permitAll()")
    @GetMapping("/{type}")
    public ResponseEntity<Response<LegalDocumentWebResponses.Document>> current(
        @Parameter(description = "문서 유형 제어 코드", schema = @Schema(allowableValues = {"TERMS", "PRIVACY"})) @PathVariable String type,
        @Parameter(description = "언어 제어 코드", schema = @Schema(allowableValues = {"ko", "en"})) @RequestParam(defaultValue = "ko") String locale
    ) {
        return ok(useCase.current(type, locale));
    }

    @Operation(summary = "이전 게시 문서 조회", description = "실제로 게시했던 리비전만 조회합니다. 미게시 초안 번호는 404입니다.")
    @PreAuthorize("permitAll()")
    @GetMapping("/{type}/revisions/{revisionNumber}")
    public ResponseEntity<Response<LegalDocumentWebResponses.Document>> revision(
        @Parameter(description = "문서 유형 제어 코드", schema = @Schema(allowableValues = {"TERMS", "PRIVACY"})) @PathVariable String type,
        @Parameter(description = "리비전 번호 (1 이상)") @PathVariable
        @Positive(message = LegalDocumentValidationMessage.REVISION) int revisionNumber,
        @Parameter(description = "언어 제어 코드", schema = @Schema(allowableValues = {"ko", "en"})) @RequestParam(defaultValue = "ko") String locale
    ) {
        return ok(useCase.revision(type, locale, revisionNumber));
    }

    private <T> ResponseEntity<Response<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(data));
    }
}
