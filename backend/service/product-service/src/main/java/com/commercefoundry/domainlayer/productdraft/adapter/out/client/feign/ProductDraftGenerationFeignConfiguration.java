package com.commercefoundry.domainlayer.productdraft.adapter.out.client.feign;

import feign.Logger;
import feign.Request;
import feign.Retryer;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;

/**
 * 상품 초안 생성 등록 Feign 의 자식 컨텍스트 전용 설정 (#359 PR-B1, 선례: {@code SizeChartGenerationFeignConfiguration}). {@code @Configuration} 을 달지 않는다 —
 * 컴포넌트 스캔으로 다른 Feign 클라이언트에 번지지 않게. 연결 2초 · 읽기 3초 · 리다이렉트 안 따름, 자동 재시도 없음(재시도는 운영자가 같은 값으로 다시 누른다),
 * 본문 로그 없음(입력 hash · 요청자가 실린다).
 */
public class ProductDraftGenerationFeignConfiguration {

    @Bean
    public Request.Options productDraftGenerationOptions() {
        return new Request.Options(2, TimeUnit.SECONDS, 3, TimeUnit.SECONDS, false);
    }

    @Bean
    public Retryer productDraftGenerationRetryer() {
        return Retryer.NEVER_RETRY;
    }

    @Bean
    public Logger.Level productDraftGenerationLoggerLevel() {
        return Logger.Level.NONE;
    }
}
