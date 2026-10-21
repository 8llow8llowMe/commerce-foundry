package com.commercefoundry.storage.client;

import com.commercefoundry.storage.properties.StorageProperties;

/** {@link ObjectStorageClient} 를 만든다. 구현은 패키지 밖에 드러내지 않는다 — SDK 타입이 서비스로 새지 않게. */
public final class ObjectStorageClients {

    private ObjectStorageClients() {
    }

    /**
     * 켜져 있으면 S3 호환 클라이언트, 꺼져 있으면 I/O 가 전부 {@code DISABLED} 인 클라이언트다.
     * 켜진 설정의 검사는 {@code StorageStartupGuard} 가 먼저 끝낸다. 여기서는 빠진 값만 막는다.
     */
    public static ObjectStorageClient create(StorageProperties properties) {
        return properties.enabled() ? S3CompatibleObjectStorageClient.create(properties) : new DisabledObjectStorageClient(properties.publicBaseUrl());
    }
}
