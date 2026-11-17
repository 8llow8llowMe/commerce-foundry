package com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.query;

import java.util.Arrays;
import java.util.function.Function;

/**
 * product 가 고정한 사이즈표 이미지 한 장의 바이트(선례: {@code ProductDraftGenerationImageQueryResult}). 바이트를 로그로 내지 않는다.
 *
 * <p>만들 때 한 번 복사해 가진다(불변). magic bytes 는 {@link #head(int)}, 해시 · AI 요청은 {@link #withBytes(Function)} 로 복사 없이 넘긴다.
 *
 * @param index 이미지 읽기 순번
 * @param bytes 바이트. 복사본을 가진다
 */
public record SizeChartGenerationImageQueryResult(int index, byte[] bytes) {

    public SizeChartGenerationImageQueryResult {
        bytes = bytes == null ? new byte[0] : bytes.clone();
    }

    /** 전체 복사본. */
    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public int length() {
        return bytes.length;
    }

    /** 앞에서 {@code count} 바이트(더 짧으면 전부)의 복사본. magic bytes 판별용이다. */
    public byte[] head(int count) {
        return Arrays.copyOf(bytes, Math.max(0, Math.min(count, bytes.length)));
    }

    /**
     * 내부 배열을 복사하지 않고 {@code reader} 에 넘긴다. {@code reader} 는 배열을 바꾸거나 보관하지 않는다
     * (예: SHA-256 계산, 생성자에서 복사하는 ai-core {@code AiImage}).
     */
    public <T> T withBytes(Function<byte[], T> reader) {
        return reader.apply(bytes);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SizeChartGenerationImageQueryResult image && index == image.index && Arrays.equals(bytes, image.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * index + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "SizeChartGenerationImageQueryResult[index=" + index + ", bytes=" + bytes.length + "]";
    }
}
