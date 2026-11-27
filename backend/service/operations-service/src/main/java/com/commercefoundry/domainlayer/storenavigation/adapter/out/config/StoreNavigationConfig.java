package com.commercefoundry.domainlayer.storenavigation.adapter.out.config;

import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationUrlPolicy;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StoreNavigationConfig {
    /** 푸터 허용 목록과 분리한다. 기본 빈 목록이면 EXTERNAL 메뉴는 모두 거부한다. */
    @Bean
    public StoreNavigationUrlPolicy storeNavigationUrlPolicy(@Value("${commerce.store.navigation.allowed-external-hosts:}") String hosts) {
        return new StoreNavigationUrlPolicy(Arrays.stream(hosts.split(",")).map(String::strip).filter(host -> !host.isEmpty()).collect(Collectors.toSet()));
    }
}
