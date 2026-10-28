package com.commercefoundry.domainlayer.display.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.request.DisplayPreviewRequest;
import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.request.DisplaySectionUpsertRequest;
import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.response.DisplayPreviewAdminResponse;
import com.commercefoundry.domainlayer.display.application.port.in.DisplayPreviewAdminUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[어드민] 메인 전시 미리보기", description = "전시 전체 초안을 저장하지 않고 고객 화면 형태로 확인")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/displays/preview")
public class DisplayPreviewAdminController {

    private final DisplayPreviewAdminUseCase displayPreviewAdminUseCase;

    @Operation(
        summary = "전시 초안 미리보기",
        description = "전체 초안을 검증하고 현재 시각에 노출되는 섹션과 고객 상품 카드를 입력 순서대로 반환한다. 숨김 · 기간 밖 섹션도 검증한다. "
            + "저장 API와 같은 PRODUCT_047~055 오류를 사용한다. 전시 조회 · 저장 · 잠금과 아이디 발급은 하지 않는다. "
            + "sectionId · itemId는 요청 배열 위치 기반 임시 문자열이며 저장 아이디가 아니다. 응답은 no-store다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<Response<DisplayPreviewAdminResponse>> preview(@RequestBody @Valid DisplayPreviewRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(
            displayPreviewAdminUseCase.preview(request.sections().stream().map(DisplaySectionUpsertRequest::toCommand).toList())
        ));
    }
}
