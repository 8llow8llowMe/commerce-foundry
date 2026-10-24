package com.commercefoundry.domainlayer.display.adapter.in.web.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.display.adapter.in.web.dto.response.DisplayMainResponse;
import com.commercefoundry.domainlayer.display.application.port.in.DisplayWebUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객 메인 전시 조회.
 *
 * <p><b>{@code Cache-Control: max-age=60, public}</b> — 메인은 모든 방문자에게 같은 응답이다(로그인 · 회원과 무관, 개인 정보 없음). 첫 화면이라 호출이 가장 많고,
 * 어드민 변경 · 노출 기간 · 상품 숨김이 반영되기까지 최대 1분 늦는 것은 받아들인다. {@code public} 이라 CDN · 공유 캐시도 담을 수 있다 —
 * 토큰을 보낸 요청도 같은 응답이라 사용자별로 달라지지 않는다. 가격 · 재고는 상세 · 장바구니 · 주문이 다시 계산하므로 1분 늦은 카드가 결제 금액을 바꾸지 않는다.
 *
 * <p><b>언어 · 통화별 응답이 생기면</b>(국문 KRW / 영문 USD 스토어프론트, {@code Accept-Language} 등 요청 헤더로 응답이 갈리면) 공유 캐시가 한 언어의 응답을
 * 다른 언어 방문자에게 내지 않도록 그 헤더를 {@code Vary} 에 싣거나 경로(쿼리 · 접두어)로 나눈다 — 지금은 응답이 하나라 {@code Vary} 를 두지 않는다.
 */
@Tag(name = "메인 전시", description = "스토어프론트 메인 화면의 전시 섹션(히어로 · 상품 픽 · 스토리 · 룩 · 디테일)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/displays")
public class DisplayWebController {

    /** 공유 캐시 · 브라우저 캐시 시간. 어드민 변경이 고객 메인에 보이기까지 늦어질 수 있는 최대 시간이기도 하다. */
    static final Duration CACHE_MAX_AGE = Duration.ofSeconds(60);

    private final DisplayWebUseCase displayWebUseCase;

    @Operation(
        summary = "메인 전시 조회",
        description = "지금 보이는 섹션(노출 켜짐 · 기간 안)을 노출 순서대로 낸다. 상품 아이템은 상품 목록 항목과 같은 카드이고, 숨긴 상품의 아이템은 빠진다 "
            + "(상품 픽의 상품이 모두 빠져도 섹션은 빈 items 로 남는다). 로그인 없이 호출할 수 있고, 응답은 60초 동안 캐시해도 된다(Cache-Control: max-age=60, public)."
    )
    @PreAuthorize("permitAll()")
    @GetMapping("/main")
    public ResponseEntity<Response<DisplayMainResponse>> getMain() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(CACHE_MAX_AGE).cachePublic())
            .body(Response.success(displayWebUseCase.getMain()));
    }
}
