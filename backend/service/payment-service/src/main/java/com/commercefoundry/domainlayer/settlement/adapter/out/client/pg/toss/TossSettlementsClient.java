package com.commercefoundry.domainlayer.settlement.adapter.out.client.pg.toss;

import java.time.LocalDate;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 토스페이먼츠 정산 조회 API (외부, #219). 직접 부르지 않는다 — {@link TossSettlementGatewayAdapter} 가 페이지 · 429 백오프 · 응답 해석을 거쳐 부른다.
 * 결제 승인 클라이언트({@code TossPaymentsClient}, {@code toss-payments})와 이름 · 타임아웃 · 설정을 나눈다 — 정산 조회의 긴 read timeout(60초)이 승인 경로에 번지지 않게.
 *
 * <ul>
 *   <li>서비스 토큰 설정({@code InternalFeignClientConfiguration})을 붙이지 않는다 — 외부 API 다 ({@code coding-conventions.md} §10). {@code url} 로 바로 간다.</li>
 *   <li>인증 헤더(Basic, 시크릿 키)는 메서드 인자다 — 인터셉터 · Capability 빈을 만들지 않는다 ({@code security-guide.md} §9-11).</li>
 *   <li>응답은 바이트로 받는다 — 본문 해석 실패를 Feign 디코딩 예외가 아니라 계약 위반으로 가르기 위해서다.</li>
 *   <li>타임아웃 · 로그 수준은 {@code spring.cloud.openfeign.client.config.toss-settlements} 이고 {@code TossSettlementsStartupGuard} 가 기동 때 본다.</li>
 * </ul>
 */
@FeignClient(name = TossSettlementsClient.NAME, url = "${toss.base-url}", configuration = TossSettlementsClientConfiguration.class)
public interface TossSettlementsClient {

    /** Feign 클라이언트 이름 — 타임아웃 설정 키 {@code spring.cloud.openfeign.client.config.toss-settlements} 와 같다. */
    String NAME = "toss-settlements";

    /** 정산 조회. 날짜는 KST 날짜(yyyy-MM-dd)이고 {@code dateType=soldDate} 면 매출일 기준이다. */
    @GetMapping(value = "/v1/settlements", produces = MediaType.APPLICATION_JSON_VALUE)
    byte[] getSettlements(
        @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
        @RequestParam("startDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam("endDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
        @RequestParam("dateType") String dateType, @RequestParam("page") int page, @RequestParam("size") int size
    );
}
