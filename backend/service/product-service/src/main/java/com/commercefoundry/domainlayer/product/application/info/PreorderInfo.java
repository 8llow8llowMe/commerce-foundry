package com.commercefoundry.domainlayer.product.application.info;

import java.time.LocalDate;

/**
 * 프리오더(예약 판매) 안내. 스토어 기능 플래그와 상품 설정이 모두 켜졌을 때만 만든다.
 *
 * @param expectedShipDate 발송 예정일 ("○월 ○일 순차 발송"). null 이면 "발송일 추후 안내" 다
 */
public record PreorderInfo(LocalDate expectedShipDate) {
}
