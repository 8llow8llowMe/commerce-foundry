package com.commercefoundry.domainlayer.productdraftgeneration.application.port.out.query;

import java.util.Arrays;
import java.util.Objects;

/**
 * product 가 고정한 이미지 한 장의 바이트. 형식은 product 응답이 아니라 생성 입력 메타데이터의 {@code contentType} 이다(바이트 응답은
 * {@code application/octet-stream}). 바이트를 로그로 내지 않는다.
 *
 * @param index     이미지 읽기 순번
 * @param mediaType 생성 입력이 고정한 형식
 * @param bytes     바이트. 복사본을 가진다
 */
public record ProductDraftGenerationImageQueryResult(int index, String mediaType, byte[] bytes) {

    public ProductDraftGenerationImageQueryResult {
        bytes = bytes == null ? new byte[0] : bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public int length() {
        return bytes.length;
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
