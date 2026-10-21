package com.commercefoundry.storage.client;

import com.commercefoundry.storage.properties.StorageProperties;
import com.commercefoundry.storage.properties.StorageProperties.Violation;
import java.util.List;
import java.util.Objects;

/** {@link ObjectStorageClient} 를 만든다. 구현은 패키지 밖에 드러내지 않는다 — SDK 타입이 서비스로 새지 않게. */
public final class ObjectStorageClients {

    private ObjectStorageClients() {
    }

    /**
     * 켜져 있으면 S3 호환 클라이언트, 꺼져 있으면 I/O 가 전부 {@code DISABLED} 인 클라이언트다.
     *
     * <p>{@code StorageStartupGuard} 와 같은 규칙({@link StorageProperties#violations()} — https · 루프백 http, 버킷 이름, 서명 TTL 범위,
     * 타임아웃 양수 · 호출 전체 상한)을 여기서도 본다. 공개 메서드라 가드를 거치지 않고 부를 수 있기 때문이다.
     *
     * @throws IllegalArgumentException 규칙에 어긋나면. 메시지에는 프로퍼티 이름과 설명만 있고 설정값은 없다
     */
    public static ObjectStorageClient create(StorageProperties properties) {
        Objects.requireNonNull(properties, "properties");
        List<Violation> violations = properties.violations();
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("Storage 설정이 규칙에 맞지 않습니다 (bootstrap-conventions.md Storage)\n- "
                + String.join("\n- ", violations.stream().map(Violation::message).toList()));
        }
        return properties.enabled() ? S3CompatibleObjectStorageClient.create(properties) : new DisabledObjectStorageClient(properties.publicBaseUrl());
    }
}
