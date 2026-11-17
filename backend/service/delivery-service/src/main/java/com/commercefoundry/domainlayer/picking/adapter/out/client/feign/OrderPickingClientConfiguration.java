package com.commercefoundry.domainlayer.picking.adapter.out.client.feign;

import feign.Logger;
import feign.Retryer;
import org.springframework.context.annotation.Bean;

/** 이 클라이언트에만 붙는 설정. {@code @Configuration} 을 달지 않는다 — 컴포넌트 스캔으로 모든 클라이언트에 퍼지지 않게. */
public class OrderPickingClientConfiguration {

    /** 전송 단계에서 다시 보내지 않는다 — 조회 실패는 운영자가 묶음 만들기를 다시 누르면 된다. Feign 이 몰래 다시 보내면 한 요청의 소요 시간만 늘어난다. */
    @Bean
    public Retryer orderPickingRetryer() {
        return Retryer.NEVER_RETRY;
    }

    /** 서비스 토큰 · 응답 본문을 로그에 남기지 않는다. */
    @Bean
    public Logger.Level orderPickingLoggerLevel() {
        return Logger.Level.NONE;
    }
}
