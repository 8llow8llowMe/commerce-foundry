package com.commercefoundry.domainlayer.storenavigation.adapter.in.web.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.storenavigation.adapter.in.web.dto.response.StoreNavigationWebResponses;
import com.commercefoundry.domainlayer.storenavigation.application.port.in.StoreNavigationWebUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "스토어 상단 메뉴", description = "실제 게시본의 요청 언어 공개 메뉴 트리만 조회")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/store/navigation")
public class StoreNavigationWebController {
    private final StoreNavigationWebUseCase useCase;

    @Operation(summary = "게시한 상단 메뉴 조회", description = "locale 생략 시 ko이며 ko·en만 허용합니다. 미등록·미게시는 404입니다.")
    @PreAuthorize("permitAll()")
    @GetMapping
    public ResponseEntity<Response<StoreNavigationWebResponses.Content>> published(
        @Parameter(description = "언어 제어 코드 (생략 시 ko)", schema = @Schema(allowableValues = {"ko", "en"}))
        @RequestParam(required = false) String locale
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(useCase.published(locale == null ? "ko" : locale)));
    }
}
