package com.commercefoundry.domainlayer.storefooter.adapter.out.config;

import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterUrlPolicy;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StoreFooterConfig {
    @Bean
    public StoreFooterUrlPolicy storeFooterUrlPolicy(@Value("${store.footer.allowed-external-hosts:}") String hosts) {
        return new StoreFooterUrlPolicy(Arrays.stream(hosts.split(",")).map(String::strip).filter(host -> !host.isEmpty()).collect(Collectors.toSet()));
    }
}
