package com.commercefoundry.domainlayer.product.adapter.in.admin.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.request.CategoryUpsertRequest;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.CategoryAdminResponse;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.response.CategoryAdminTreeResponse;
import com.commercefoundry.domainlayer.product.application.exception.ProductValidationMessage;
import com.commercefoundry.domainlayer.product.application.port.in.CategoryAdminUseCase;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 어드민 카테고리 관리. {@code /api/v1/admin/**} 은 URL 규칙이 ADMIN 을 먼저 요구하고, 메서드의 {@code hasRole('ADMIN')} 이 이중으로 막는다
 * ({@code security-guide.md} §5-1). 카테고리 표에 작성자 · 수정자 칸이 없어 어드민 memberId 를 받지 않는다.
 *
 * <p>상품 컨텍스트에 둔다 — 삭제 판정의 "연결된 상품" 을 상품 컨텍스트가 안다. 고객 트리({@code CategoryWebController})와 DTO 를 공유하지 않는다.
 * 파라미터 검증은 Spring MVC 의 메서드 검증이 한다 — 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 */
@Tag(name = "[어드민] 카테고리 관리", description = "숨김 포함 카테고리 트리 조회 · 생성 · 수정(이동 포함) · 삭제")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/categories")
public class CategoryAdminController {

    private final CategoryAdminUseCase categoryAdminUseCase;

    @Operation(
        summary = "카테고리 트리 조회 (숨김 포함)",
        description = "어드민 편집용 전체 트리다. 숨긴 카테고리와 그 하위도 나오고 visible · parentId 를 함께 낸다. "
            + "같은 상위 안에서 노출 순서 → 아이디 오름차순이다. 깊이는 최대 3단계(대·중·소)다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<Response<CategoryAdminTreeResponse>> getCategoryTree() {
        return ResponseEntity.ok().body(Response.success(categoryAdminUseCase.getCategoryTree()));
    }

    @Operation(
        summary = "카테고리 생성",
        description = "parentId 가 없으면 최상위로 만든다. 없는 상위는 400(PRODUCT_044), 3단계를 넘으면 400(PRODUCT_040)이다. 201 과 만든 카테고리를 돌려준다."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<Response<CategoryAdminResponse>> createCategory(@RequestBody @Valid CategoryUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.success(categoryAdminUseCase.createCategory(request.toCommand())));
    }

    @Operation(
        summary = "카테고리 수정 (전체 교체 · 이동 포함)",
        description = "본문은 생성과 같고 모든 칸을 바꾼다. parentId 를 바꾸면 하위까지 함께 옮기며, 옮긴 뒤 하위까지 3단계를 넘으면 400(PRODUCT_040), "
            + "자기 자신 · 자기 하위를 상위로 지정하면 400(PRODUCT_041), 없는 상위는 400(PRODUCT_044)이다. 없는 카테고리는 404(PRODUCT_004)다. "
            + "메뉴에서 감추려면 visible=false 로 보낸다 (하위까지 메뉴에서 빠지고, 상품 노출은 막지 않는다)."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{categoryId}")
    public ResponseEntity<Response<CategoryAdminResponse>> updateCategory(
        @Parameter(description = "카테고리 아이디") @PathVariable @Positive(message = ProductValidationMessage.CATEGORY_ID_POSITIVE) long categoryId,
        @RequestBody @Valid CategoryUpsertRequest request
    ) {
        return ResponseEntity.ok().body(Response.success(categoryAdminUseCase.updateCategory(categoryId, request.toCommand())));
    }

    @Operation(
        summary = "카테고리 삭제",
        description = "하위 카테고리가 있으면 409(PRODUCT_042), 상품의 대표 · 진열 카테고리로 연결돼 있으면(숨김 · 판매 종료 상품 포함) 409(PRODUCT_043)다 — "
            + "연결을 자동으로 풀지 않는다. 메뉴에서만 감추려면 삭제 대신 visible=false 로 수정한다. 없는 카테고리는 404(PRODUCT_004)다 "
            + "(삭제 성공 뒤 같은 요청을 다시 보내도 404)."
    )
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{categoryId}")
    public ResponseEntity<Response<Void>> deleteCategory(
        @Parameter(description = "카테고리 아이디") @PathVariable @Positive(message = ProductValidationMessage.CATEGORY_ID_POSITIVE) long categoryId
    ) {
        categoryAdminUseCase.deleteCategory(categoryId);
        return ResponseEntity.ok().body(Response.success());
    }
}
