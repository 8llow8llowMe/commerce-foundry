package com.commercefoundry.domainlayer.look.adapter.in.web.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.look.adapter.in.web.dto.response.LookDetailResponse;
import com.commercefoundry.domainlayer.look.adapter.in.web.dto.response.LookSummaryResponse;
import com.commercefoundry.domainlayer.look.application.exception.LookValidationMessage;
import com.commercefoundry.domainlayer.look.application.port.in.LookWebUseCase;
import com.commercefoundry.persistence.dto.SliceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객 룩(Shop the Look) 조회. 상품별 룩은 {@link ProductLookWebController} 다. 어드민({@code LookAdminController})과 DTO 를 공유하지 않는다.
 *
 * <p><b>{@code Cache-Control: max-age=60, public}</b> — 메인 전시({@code DisplayWebController})와 같은 이유다. 모든 방문자에게 같은 응답이고(로그인 · 회원과 무관,
 * 개인 정보 없음) 어드민 변경 · 상품 숨김이 최대 1분 늦게 보이는 것을 받아들인다. 가격 · 재고는 상세 · 장바구니 · 주문이 다시 계산한다.
 * 언어 · 통화별 응답이 생기면 {@code Vary} 를 두거나 경로를 나눈다.
 */
@Tag(name = "룩", description = "스토어프론트 룩(Shop the Look) — 대표 이미지와 구성 상품 카드")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/looks")
public class LookWebController {

    /** 공유 캐시 · 브라우저 캐시 시간. 어드민 변경이 고객 화면에 보이기까지 늦어질 수 있는 최대 시간이기도 하다. */
    static final Duration CACHE_MAX_AGE = Duration.ofSeconds(60);

    private static final int MAX_PAGE_SIZE = 100;

    private final LookWebUseCase lookWebUseCase;

    @Operation(
        summary = "룩 목록 조회",
        description = "보이는 룩을 노출 순서대로 한 쪽씩 낸다(무한 스크롤 — 전체 건수 없음). 구성 상품은 상품 목록 항목과 같은 카드이고 숨긴 상품은 빠진다 "
            + "(모두 빠지면 products 가 빈 배열). 룩 기능이 꺼져 있으면 빈 목록이다. 로그인 없이 호출할 수 있고, 60초 동안 캐시해도 된다."
    )
    @PreAuthorize("permitAll()")
    @GetMapping
    public ResponseEntity<Response<SliceResponse<LookSummaryResponse>>> getLooks(
        @Parameter(description = "페이지 (0부터)") @RequestParam(defaultValue = "0") @PositiveOrZero(message = LookValidationMessage.PAGE_MIN) int page,
        @Parameter(description = "페이지 크기 (1~100)") @RequestParam(defaultValue = "20")
        @Min(value = 1, message = LookValidationMessage.SIZE_RANGE) @Max(value = MAX_PAGE_SIZE, message = LookValidationMessage.SIZE_RANGE) int size
    ) {
        return ResponseEntity.ok().cacheControl(cacheControl()).body(Response.success(lookWebUseCase.getLooks(page, size)));
    }

    @Operation(
        summary = "룩 상세 조회",
        description = "없거나 숨긴 룩, 룩 기능이 꺼져 있으면 404(LOOK_001)다. 구성 상품 규칙은 목록과 같다. 로그인 없이 호출할 수 있고, 60초 동안 캐시해도 된다."
    )
    @PreAuthorize("permitAll()")
    @GetMapping("/{lookId}")
    public ResponseEntity<Response<LookDetailResponse>> getLook(
        @Parameter(description = "룩 아이디") @PathVariable @Positive(message = LookValidationMessage.LOOK_ID_POSITIVE) long lookId
    ) {
        return ResponseEntity.ok().cacheControl(cacheControl()).body(Response.success(lookWebUseCase.getLook(lookId)));
    }

    static CacheControl cacheControl() {
        return CacheControl.maxAge(CACHE_MAX_AGE).cachePublic();
    }
}
