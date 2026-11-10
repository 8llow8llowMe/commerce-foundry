package com.commercefoundry.domainlayer.order.support;

import com.commercefoundry.domainlayer.order.adapter.in.web.presenter.OrderHistoryWebPresenter;
import com.commercefoundry.domainlayer.order.adapter.in.web.presenter.OrderQuoteWebPresenter;
import com.commercefoundry.domainlayer.order.adapter.in.web.presenter.OrderReturnWebPresenter;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.ClaimRepositoryAdapter;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.OrderPaymentRepositoryAdapter;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.OrderRepositoryAdapter;
import com.commercefoundry.domainlayer.order.application.mapper.ClaimMapper;
import com.commercefoundry.domainlayer.order.application.mapper.OrderMapperImpl;
import com.commercefoundry.domainlayer.order.application.mapper.OrderPaymentMapperImpl;
import com.commercefoundry.domainlayer.order.application.port.out.OrderEventPort;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentCancelPort;
import com.commercefoundry.domainlayer.order.application.port.out.StockRestorationPort;
import com.commercefoundry.domainlayer.order.application.service.OrderHistoryWebFacade;
import com.commercefoundry.domainlayer.order.application.service.OrderReturnWebFacade;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationPipelineProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderCancellationProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderClaimHistoryProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderHistoryProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderReturnProcessor;
import com.commercefoundry.global.config.OrderServiceFeaturesConfig;
import com.commercefoundry.global.properties.OrderCancellationProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * 반품 접수 · 결제 후 취소 클레임 영속성 계약의 빈 — 결제 후 취소 흐름(#16 PR-2)은 실제 Facade · Processor · DB 에 payment · product 호출만 목으로 둔다.
 * 결제 후 취소는 열어 둔다(재시도 켜짐 — 이 컨텍스트에는 스케줄러 빈이 없다).
 */
@TestConfiguration
@Import({OrderServiceFeaturesConfig.class, OrderRepositoryAdapter.class, OrderMapperImpl.class, ClaimRepositoryAdapter.class, ClaimMapper.class,
    OrderReturnProcessor.class, OrderReturnWebFacade.class, OrderReturnWebPresenter.class, OrderHistoryProcessor.class, OrderClaimHistoryProcessor.class,
    OrderPaymentRepositoryAdapter.class, OrderPaymentMapperImpl.class, OrderCancellationProcessor.class, OrderCancellationPipelineProcessor.class,
    OrderHistoryWebFacade.class, OrderHistoryWebPresenter.class, OrderQuoteWebPresenter.class})
public class OrderReturnTestConfig {
    @Bean
    SnowflakeIdGenerator snowflakeIdGenerator() {
        return new SnowflakeIdGenerator(3, 3);
    }

    @Bean
    OrderEventPort orderEventPort() {
        return org.mockito.Mockito.mock(OrderEventPort.class);
    }

    @Bean
    PaymentCancelPort paymentCancelPort() {
        return org.mockito.Mockito.mock(PaymentCancelPort.class);
    }

    @Bean
    StockRestorationPort stockRestorationPort() {
        return org.mockito.Mockito.mock(StockRestorationPort.class);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    OrderCancellationProperties orderCancellationProperties() {
        return new OrderCancellationProperties(false, new OrderCancellationProperties.Retry(true, Duration.ofMinutes(1), 50, 48, Duration.ofMinutes(1),
            Duration.ofHours(1), Duration.ofMinutes(2)));
    }
}
