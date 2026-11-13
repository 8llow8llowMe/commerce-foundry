package com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/**
 * product 가 고정한 이미지 한 장의 바이트. 바이트를 로그로 내지 않는다.
 *
 * <p>바이트는 만들 때 한 번 복사해 가진다(불변). 읽을 때는 필요한 만큼만 복사한다 — magic bytes 는 {@link #head(int)}, AI 요청은
 * {@link #withBytes(Function)} 로 받는 쪽이 한 번만 복사한다. {@link #bytes()} 는 전체 복사본이다.
 *
 * @param index     이미지 읽기 순번
 * @param mediaType 응답 {@code Content-Type} 헤더에서 읽은 형식(파라미터를 뺀 소문자). product 계약은 {@code application/octet-stream} 이다 —
 *                  실제 이미지 형식은 생성 입력 메타데이터 {@code contentType} 과 magic bytes 로 검증 Processor 가 정한다
 * @param bytes     바이트. 복사본을 가진다
 */
public record ProductDraftGenerationImageQueryResult(int index, String mediaType, byte[] bytes) {

    public ProductDraftGenerationImageQueryResult {
        bytes = bytes == null ? new byte[0] : bytes.clone();
    }

    /** 전체 복사본. 앞부분만 필요하면 {@link #head(int)} 를 쓴다. */
    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public int length() {
        return bytes.length;
    }

    /** 앞에서 {@code count} 바이트(바이트가 더 짧으면 전부)의 복사본. magic bytes 판별용이다. */
    public byte[] head(int count) {
        return Arrays.copyOf(bytes, Math.max(0, Math.min(count, bytes.length)));
    }

    /**
     * 내부 배열을 복사하지 않고 {@code copier} 에 넘긴다 — 장당 5MiB 까지의 바이트를 AI 요청에 실을 때 복사를 한 번으로 줄이는 통로다.
     * {@code copier} 는 배열을 바꾸거나 보관하지 않고 바로 복사해야 한다(예: 생성자에서 복사하는 ai-core {@code AiImage}).
     */
    public <T> T withBytes(Function<byte[], T> copier) {
        return copier.apply(bytes);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProductDraftGenerationImageQueryResult image
            && index == image.index && Objects.equals(mediaType, image.mediaType) && Arrays.equals(bytes, image.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * index + Objects.hashCode(mediaType)) + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "ProductDraftGenerationImageQueryResult[index=" + index + ", mediaType=" + mediaType + ", bytes=" + bytes.length + "]";
    }
}
