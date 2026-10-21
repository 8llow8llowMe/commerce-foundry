package com.commercefoundry.storage.client;

import java.util.Optional;

/**
 * S3 호환 오브젝트 스토리지 클라이언트. 도메인을 모른다 — 키 규칙(어느 prefix 에 무엇을 두는가), 허용 형식 · 용량은 쓰는 서비스가 정한다.
 *
 * <p>서비스는 이 인터페이스를 out-port 의 어댑터({@code adapter/out/client/storage}) 안에서만 쓰고, {@link StorageException} 을
 * 도메인 오류 코드로 번역한다 ({@code architecture-guide.md} §8). SDK 타입은 드러나지 않는다.
 *
 * <p>키는 버킷 안의 경로({@code uploads/2f1c….jpg})다. 비었거나 {@code /} 로 시작하거나 빈 · {@code .} · {@code ..} 구간이 있거나
 * 제어 문자가 있으면 {@link IllegalArgumentException} 이다 — 호출한 코드의 버그이지 스토리지 장애가 아니다.
 *
 * <p>{@code storage.enabled=false} 면 I/O 메서드가 전부 {@link StorageException.Reason#DISABLED} 를 던진다.
 */
public interface ObjectStorageClient {

    /**
     * 브라우저가 스토리지에 바로 올리는 PUT 서명 URL 을 만든다. 네트워크를 쓰지 않는다(로컬 서명).
     * Content-Type · Content-Length 를 서명에 넣으므로 다른 형식 · 길이로 올리면 스토리지가 거절한다 — 강제 여부는 제공자마다 달라
     * 받은 뒤 {@link #head} 로 다시 확인한다.
     *
     * @param contentType   올릴 형식 — 서명에 들어가 업로드 요청의 Content-Type 과 같아야 한다
     * @param contentLength 올릴 바이트 수, 1 이상
     * @return 만료는 {@code storage.presign-ttl} 뒤
     */
    PresignedUpload presignPut(String key, String contentType, long contentLength);

    /** 객체의 크기 · 형식 · ETag. 없으면 비어 있다. */
    Optional<StoredObject> head(String key);

    /**
     * 객체 앞부분을 최대 {@code length} 바이트 읽는다(형식 판별용 — {@code ImageFormat.DETECTION_LENGTH}).
     * 객체가 더 짧으면 있는 만큼, 빈 객체면 빈 배열이다. 없으면 {@link StorageException.Reason#NOT_FOUND} 다.
     *
     * @param length 1 이상
     */
    byte[] readRange(String key, int length);

    /**
     * 같은 버킷 안에서 서버 측 복사한다. 메타데이터는 원본을 잇지 않고 인자로 바꾼다(업로더가 붙인 메타데이터가 따라오지 않게).
     * 원본이 없으면 {@link StorageException.Reason#NOT_FOUND} 다.
     *
     * @param contentType  복사본의 Content-Type
     * @param cacheControl 복사본의 Cache-Control. null 이면 붙이지 않는다
     */
    void copy(String sourceKey, String targetKey, String contentType, String cacheControl);

    /** 객체를 지운다. 없는 키여도 성공이다(멱등). */
    void delete(String key);

    /**
     * 바이트를 그대로 올린다 — 서버가 가진 데이터를 옮길 때 쓴다(카페24 이관 #19). 브라우저 업로드는 {@link #presignPut} 이다.
     *
     * @param cacheControl null 이면 붙이지 않는다
     */
    void put(String key, byte[] bytes, String contentType, String cacheControl);

    /**
     * 공개 읽기 주소 {@code {storage.public-base-url}/{key}} 다. 키의 각 구간을 퍼센트 인코딩한다(공백 · 한글 등). I/O 가 없다.
     * 스토리지가 꺼져 있어도 공개 주소가 설정돼 있으면 만든다. 없으면 {@link StorageException.Reason#DISABLED} 다.
     */
    String publicUrlOf(String key);
}
