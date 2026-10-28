package com.commercefoundry.domainlayer.look.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.request.LookOrderRequest;
import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.request.LookUpsertRequest;
import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.response.LookAdminListResponse;
import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.response.LookAdminResponse;
import com.commercefoundry.domainlayer.look.application.exception.LookValidationMessage;
import com.commercefoundry.domainlayer.look.application.port.in.LookAdminUseCase;
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
 * 어드민 룩(Shop the Look) 관리. {@code /api/v1/admin/**} 은 URL 규칙이 ADMIN 을 먼저 요구하고, 메서드의 {@code hasRole('ADMIN')} 이 이중으로 막는다
 * ({@code security-guide.md} §5-1). 룩 표에 작성자 · 수정자 칸이 없어 어드민 memberId 를 받지 않는다. 기능 플래그({@code store.features.looks})와 무관하게 동작한다.
 * 파라미터 검증은 Spring MVC 의 메서드 검증이 한다 — 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 *
 * <p>순서 변경({@code PATCH /order})은 경로 변수 {@code {lookId}} 와 같은 깊이지만 메서드가 달라(PATCH 는 이 경로 하나뿐) 겹치지 않는다.
 */
@Tag(name = "[어드민] 룩 관리", description = "룩(Shop the Look — 대표 이미지 + 구성 상품) 조회 · 생성 · 수정 · 삭제 · 순서 변경")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/looks")
public class LookAdminController {

    private final LookAdminUseCase lookAdminUseCase;

    @Operation(
        summary = "룩 전체 조회",
        description = "숨긴 룩을 포함한 전체(최대 100개)를 노출 순서대로 낸다(배열 순서 = 노출 순서). "
            + "구성 상품은 상품 아이디 · 이름 · 노출 여부 · 판매 상태만 낸다(숨김 · 판매 종료 상품 포함)."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Response<LookAdminListResponse>> getLooks() {
        return ResponseEntity.ok().body(Response.success(lookAdminUseCase.getLooks()));
    }

    @Operation(summary = "룩 단건 조회", description = "없는 룩은 404(LOOK_001)다.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/{lookId}")
    public ResponseEntity<Response<LookAdminResponse>> getLook(
        @Parameter(description = "룩 아이디") @PathVariable @Positive(message = LookValidationMessage.LOOK_ID_POSITIVE) long lookId
    ) {
        return ResponseEntity.ok().body(Response.success(lookAdminUseCase.getLook(lookId)));
    }

    @Operation(
        summary = "룩 생성",
        description = "룩과 구성 상품을 만들고 맨 뒤 순서에 둔다. 같은 상품 중복은 400(LOOK_003), 없는 상품은 400(LOOK_004), 저장소 밖 이미지 주소는 400(LOOK_005), "
            + "미디어 저장소 미설정은 503(LOOK_006), 룩 100개 초과는 409(LOOK_002), 다른 룩 변경과 겹치면 409(LOOK_008)다. 201 과 만든 룩을 돌려준다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<Response<LookAdminResponse>> create(@RequestBody @Valid LookUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.success(lookAdminUseCase.create(request.toCommand())));
    }

    @Operation(
        summary = "룩 수정 (전체 교체)",
        description = "본문은 생성과 같고 룩 칸과 구성 상품 전체를 바꾼다(노출 순서는 그대로). 없는 룩은 404(LOOK_001)이고 그 밖의 오류는 생성과 같다. "
            + "버전 칸이 없어 동시에 고치면 마지막 쓰기가 이긴다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{lookId}")
    public ResponseEntity<Response<LookAdminResponse>> update(
        @Parameter(description = "룩 아이디") @PathVariable @Positive(message = LookValidationMessage.LOOK_ID_POSITIVE) long lookId,
        @RequestBody @Valid LookUpsertRequest request
    ) {
        return ResponseEntity.ok().body(Response.success(lookAdminUseCase.update(lookId, request.toCommand())));
    }

    @Operation(
        summary = "룩 삭제",
        description = "룩과 구성 상품을 함께 지운다(행 삭제). 없는 룩은 404(LOOK_001)다 — 삭제 성공 뒤 같은 요청을 다시 보내도 404 다. 남은 룩의 순서는 다시 매기지 않는다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{lookId}")
    public ResponseEntity<Response<Void>> delete(
        @Parameter(description = "룩 아이디") @PathVariable @Positive(message = LookValidationMessage.LOOK_ID_POSITIVE) long lookId
    ) {
        lookAdminUseCase.delete(lookId);
        return ResponseEntity.ok().body(Response.success());
    }

    @Operation(
        summary = "룩 순서 변경",
        description = "지금 있는 룩 아이디 전체를 새 순서대로 보낸다 — 받은 순서대로 노출 순서를 매긴다. 누락 · 중복 · 모르는 아이디는 400(LOOK_007)이다. "
            + "응답은 새 순서의 전체 목록이다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/order")
    public ResponseEntity<Response<LookAdminListResponse>> reorder(@RequestBody @Valid LookOrderRequest request) {
        return ResponseEntity.ok().body(Response.success(lookAdminUseCase.reorder(request.lookIds())));
    }
}
