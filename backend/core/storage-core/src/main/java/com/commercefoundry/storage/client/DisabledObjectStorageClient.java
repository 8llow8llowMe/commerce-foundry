package com.commercefoundry.storage.client;

import com.commercefoundry.storage.client.StorageException.Operation;
import com.commercefoundry.storage.client.StorageException.Reason;
import java.util.Optional;

/**
 * {@code storage.enabled=false} 일 때의 클라이언트. I/O 는 전부 {@link Reason#DISABLED} 다.
 * 공개 주소가 설정돼 있으면 {@link #publicUrlOf} 만 동작한다 — 이미 올린 이미지는 스토리지를 꺼도 보여 줄 수 있다.
 */
final class DisabledObjectStorageClient implements ObjectStorageClient {

    /** 끝 {@code /} 를 지운 공개 주소. 없으면 null. */
    private final String publicBaseUrl;

    DisabledObjectStorageClient(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public PresignedUpload presignPut(String key, String contentType, long contentLength) {
        throw disabled(Operation.PRESIGN_PUT);
    }

    @Override
    public Optional<StoredObject> head(String key) {
        throw disabled(Operation.HEAD);
    }

    @Override
    public byte[] readRange(String key, int length) {
        throw disabled(Operation.READ_RANGE);
    }

    @Override
    public void copy(String sourceKey, String targetKey, String contentType, String cacheControl) {
        throw disabled(Operation.COPY);
    }

    @Override
    public void delete(String key) {
        throw disabled(Operation.DELETE);
    }

    @Override
    public void put(String key, byte[] bytes, String contentType, String cacheControl) {
        throw disabled(Operation.PUT);
    }

    @Override
    public String publicUrlOf(String key) {
        if (publicBaseUrl == null) {
            throw disabled(Operation.PUBLIC_URL);
        }
        return ObjectKeys.publicUrl(publicBaseUrl, key);
    }

    private static StorageException disabled(Operation operation) {
        return new StorageException(Reason.DISABLED, operation);
    }
}
