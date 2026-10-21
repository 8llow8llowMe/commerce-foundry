package com.commercefoundry.storage.config;

import com.commercefoundry.storage.client.ObjectStorageClient;
import com.commercefoundry.storage.client.ObjectStorageClients;
import com.commercefoundry.storage.properties.StorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 스토리지를 쓰는 서비스의 {@code *BeansConfig} 가 {@code @Import} 한다 ({@code bootstrap-conventions.md} Storage). 프로퍼티는
 * {@link StoragePropertiesConfig} 가 등록한다. 컴포넌트 스캔에 걸리지 않게 {@code @Configuration} 을 달지 않는다.
 *
 * <p>{@code storage.enabled=false} 여도 {@link ObjectStorageClient} 빈은 언제나 하나 있다. 꺼져 있으면 I/O 메서드가 전부
 * {@code StorageException(DISABLED)} 를 던지므로, 서비스 어댑터는 빈 유무를 분기하지 않고 예외 하나를 503 으로 번역한다.
 * 빈이 없게 하면 어댑터마다 {@code ObjectProvider} 분기가 생기고, 빠뜨린 곳은 기동 실패나 NPE 로 드러난다.
 */
@Slf4j
public class StorageConfigurer {

    /**
     * static — {@code BeanFactoryPostProcessor} 는 설정 클래스 인스턴스보다 먼저 만들어져야 한다.
     * 클라이언트 · 프로퍼티 빈이 생기기 전에 설정을 검사한다.
     */
    @Bean
    public static StorageStartupGuard storageStartupGuard(Environment environment) {
        return new StorageStartupGuard(environment);
    }

    /** SDK 클라이언트는 {@code close()} 로 닫는다 — 스프링이 종료 때 추론해 부른다. */
    @Bean
    public ObjectStorageClient objectStorageClient(StorageProperties storageProperties) {
        if (!storageProperties.enabled()) {
            log.info("object storage disabled. storage.enabled=false, publicUrl={}", storageProperties.publicBaseUrl() != null);
        }
        return ObjectStorageClients.create(storageProperties);
    }
}
