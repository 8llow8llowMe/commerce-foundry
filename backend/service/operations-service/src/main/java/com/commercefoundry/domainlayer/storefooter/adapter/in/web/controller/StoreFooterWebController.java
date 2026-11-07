package com.commercefoundry.domainlayer.storefooter.adapter.in.web.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.storefooter.adapter.in.web.dto.response.StoreFooterWebResponses;
import com.commercefoundry.domainlayer.storefooter.application.port.in.StoreFooterWebUseCase;
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

@Tag(name = "스토어 푸터", description = "실제 게시본의 요청 언어·공개 링크만 조회")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/store/footer")
public class StoreFooterWebController {
    private final StoreFooterWebUseCase useCase;

    @Operation(summary = "게시한 푸터 조회", description = "locale 생략 시 ko이며 ko·en만 허용합니다. 미등록·미게시는 404입니다.")
    @PreAuthorize("permitAll()")
    @GetMapping
    public ResponseEntity<Response<StoreFooterWebResponses.Content>> published(
        @Parameter(description = "언어 제어 코드 (생략 시 ko)", schema = @Schema(allowableValues = {"ko", "en"}))
        @RequestParam(required = false) String locale
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(useCase.published(locale == null ? "ko" : locale)));
    }
}
