package com.commercefoundry.domainlayer.order.support;

import com.commercefoundry.domainlayer.order.adapter.in.web.presenter.OrderReturnWebPresenter;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.ClaimRepositoryAdapter;
import com.commercefoundry.domainlayer.order.adapter.out.persistence.OrderRepositoryAdapter;
import com.commercefoundry.domainlayer.order.application.mapper.ClaimMapper;
import com.commercefoundry.domainlayer.order.application.mapper.OrderMapperImpl;
import com.commercefoundry.domainlayer.order.application.port.out.OrderEventPort;
import com.commercefoundry.domainlayer.order.application.service.OrderReturnWebFacade;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderHistoryProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderReturnProcessor;
import com.commercefoundry.global.config.OrderServiceFeaturesConfig;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@TestConfiguration
@Import({OrderServiceFeaturesConfig.class, OrderRepositoryAdapter.class, OrderMapperImpl.class, ClaimRepositoryAdapter.class, ClaimMapper.class,
    OrderReturnProcessor.class, OrderReturnWebFacade.class, OrderReturnWebPresenter.class, OrderHistoryProcessor.class})
public class OrderReturnTestConfig {
    @Bean
    SnowflakeIdGenerator snowflakeIdGenerator() {
        return new SnowflakeIdGenerator(3, 3);
    }

    @Bean
    OrderEventPort orderEventPort() {
        return org.mockito.Mockito.mock(OrderEventPort.class);
    }
}
