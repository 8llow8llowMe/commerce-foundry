package com.commercefoundry.domainlayer.display.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.request.DisplaySectionOrderRequest;
import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.request.DisplaySectionUpsertRequest;
import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.response.DisplaySectionAdminListResponse;
import com.commercefoundry.domainlayer.display.adapter.in.admin.dto.response.DisplaySectionAdminResponse;
import com.commercefoundry.domainlayer.display.application.exception.DisplayValidationMessage;
import com.commercefoundry.domainlayer.display.application.port.in.DisplayAdminUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 메인 전시 관리. {@code /api/v1/admin/**} 은 URL 규칙이 ADMIN 을 먼저 요구하고, 메서드의 {@code hasRole('ADMIN')} 이 이중으로 막는다
 * ({@code security-guide.md} §5-1). 전시 표에 작성자 · 수정자 칸이 없어 어드민 memberId 를 받지 않는다. 고객 메인({@code DisplayWebController})과 DTO 를 공유하지 않는다.
 * 파라미터 검증은 Spring MVC 의 메서드 검증이 한다 — 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 *
 * <p>순서 변경({@code PATCH .../sections/order})은 경로 변수 {@code {sectionId}} 와 같은 깊이지만 메서드가 달라(PATCH 는 이 경로 하나뿐) 겹치지 않는다.
 */
@Tag(name = "[어드민] 메인 전시 관리", description = "메인 전시 섹션(히어로 · 상품 픽 · 스토리 · 룩 · 디테일) 조회 · 생성 · 수정 · 삭제 · 순서 변경")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/displays/sections")
public class DisplayAdminController {

    private final DisplayAdminUseCase displayAdminUseCase;

    @Operation(
        summary = "전시 섹션 전체 조회",
        description = "숨김 · 기간 밖 섹션을 포함한 전체(최대 50개)를 노출 순서 → 아이디 오름차순으로 낸다. isLive 는 지금 고객 메인에 보이는지다. "
            + "상품 아이템은 상품 아이디 · 이름 · 노출 여부만 낸다(숨긴 상품 포함)."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Response<DisplaySectionAdminListResponse>> getSections() {
        return ResponseEntity.ok().body(Response.success(displayAdminUseCase.getSections()));
    }

    @Operation(summary = "전시 섹션 단건 조회", description = "없는 섹션은 404(PRODUCT_046)다.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{sectionId}")
    public ResponseEntity<Response<DisplaySectionAdminResponse>> getSection(
        @Parameter(description = "섹션 아이디") @PathVariable @Positive(message = DisplayValidationMessage.SECTION_ID_POSITIVE) long sectionId
    ) {
        return ResponseEntity.ok().body(Response.success(displayAdminUseCase.getSection(sectionId)));
    }

    @Operation(
        summary = "전시 섹션 생성",
        description = "섹션과 아이템 전체를 만들고 맨 뒤 순서에 둔다. 유형별 아이템 규칙 위반은 400(PRODUCT_047 · 048), 같은 상품 중복은 400(PRODUCT_049), "
            + "없는 상품은 400(PRODUCT_050), 기간 · 링크 오류는 400(PRODUCT_051~053), 저장소 밖 미디어 주소는 400(PRODUCT_054), "
            + "미디어 저장소 미설정은 503(PRODUCT_055), 섹션 50개 초과는 409(PRODUCT_056), 다른 전시 변경과 겹치면 409(PRODUCT_058)다. 201 과 만든 섹션을 돌려준다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<Response<DisplaySectionAdminResponse>> createSection(@RequestBody @Valid DisplaySectionUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.success(displayAdminUseCase.createSection(request.toCommand())));
    }

    @Operation(
        summary = "전시 섹션 수정 (전체 교체)",
        description = "본문은 생성과 같고 섹션 칸과 아이템 전체를 바꾼다(노출 순서는 그대로, 아이템 아이디는 새로 발급). 없는 섹션은 404(PRODUCT_046)이고 그 밖의 오류는 생성과 같다. "
            + "버전 칸이 없어 동시에 고치면 마지막 쓰기가 이긴다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{sectionId}")
    public ResponseEntity<Response<DisplaySectionAdminResponse>> updateSection(
        @Parameter(description = "섹션 아이디") @PathVariable @Positive(message = DisplayValidationMessage.SECTION_ID_POSITIVE) long sectionId,
        @RequestBody @Valid DisplaySectionUpsertRequest request
    ) {
        return ResponseEntity.ok().body(Response.success(displayAdminUseCase.updateSection(sectionId, request.toCommand())));
    }

    @Operation(
        summary = "전시 섹션 삭제",
        description = "섹션과 아이템을 함께 지운다(행 삭제). 없는 섹션은 404(PRODUCT_046)다 — 삭제 성공 뒤 같은 요청을 다시 보내도 404 다. 남은 섹션의 순서는 다시 매기지 않는다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{sectionId}")
    public ResponseEntity<Response<Void>> deleteSection(
        @Parameter(description = "섹션 아이디") @PathVariable @Positive(message = DisplayValidationMessage.SECTION_ID_POSITIVE) long sectionId
    ) {
        displayAdminUseCase.deleteSection(sectionId);
        return ResponseEntity.ok().body(Response.success());
    }

    @Operation(
        summary = "전시 섹션 순서 변경",
        description = "지금 있는 섹션 아이디 전체를 새 순서대로 보낸다 — 받은 순서대로 0..n-1 을 매긴다. 누락 · 중복 · 모르는 아이디는 400(PRODUCT_057)이다. "
            + "응답은 새 순서의 전체 목록이다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/order")
    public ResponseEntity<Response<DisplaySectionAdminListResponse>> reorderSections(@RequestBody @Valid DisplaySectionOrderRequest request) {
        return ResponseEntity.ok().body(Response.success(displayAdminUseCase.reorderSections(request.sectionIds())));
    }
}
