package com.commercefoundry.domainlayer.shipment.adapter.out.client.feign;

import feign.Logger;
import feign.Retryer;
import org.springframework.context.annotation.Bean;

/** 이 클라이언트에만 붙는 설정. {@code @Configuration} 을 달지 않는다 — 컴포넌트 스캔으로 모든 클라이언트에 퍼지지 않게. */
public class OrderShipmentClientConfiguration {

    /**
     * 전송 단계에서 다시 보내지 않는다 — 결과 모름(타임아웃 등)은 송장 행을 남기고 운영자의 같은 요청 재시도로 수렴한다(order 멱등).
     * Feign 이 몰래 다시 보내면 한 요청의 소요 시간만 늘어난다.
     */
    @Bean
    public Retryer orderShipmentRetryer() {
        return Retryer.NEVER_RETRY;
    }

    /** 서비스 토큰 · 응답 본문을 로그에 남기지 않는다. */
    @Bean
    public Logger.Level orderShipmentLoggerLevel() {
        return Logger.Level.NONE;
    }
}
