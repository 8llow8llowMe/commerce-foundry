package com.commercefoundry.domainlayer.category.adapter.in.web.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.category.adapter.in.web.dto.response.CategoryTreeResponse;
import com.commercefoundry.domainlayer.category.application.port.in.CategoryWebUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "카테고리", description = "스토어프론트 카테고리 메뉴")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/categories")
public class CategoryWebController {

    private final CategoryWebUseCase categoryWebUseCase;

    @Operation(
        summary = "카테고리 트리 조회",
        description = "헤더 메뉴용 카테고리 트리다. 숨긴 카테고리는 그 하위까지 빠지고, 같은 상위 안에서 노출 순서 → 아이디 오름차순이다. 로그인 없이 호출할 수 있다."
    )
    @PreAuthorize("permitAll()")
    @GetMapping
    public ResponseEntity<Response<CategoryTreeResponse>> getCategoryTree() {
        return ResponseEntity.ok().body(Response.success(categoryWebUseCase.getCategoryTree()));
    }
}
