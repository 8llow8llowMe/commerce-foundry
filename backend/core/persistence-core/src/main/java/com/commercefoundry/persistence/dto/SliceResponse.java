package com.commercefoundry.persistence.dto;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Slice;

/**
 * 스토어프론트 목록(무한 스크롤)용 — 전체 건수를 세지 않는다 ({@code api-design-guide.md} §4).
 */
public record SliceResponse<T>(List<T> contents, boolean hasNext) {

    public static <T> SliceResponse<T> of(Slice<T> slice) {
        return new SliceResponse<>(slice.getContent(), slice.hasNext());
    }

    /** Presenter 가 항목을 응답 DTO 로 바꿀 때 쓴다. {@code hasNext} 는 그대로 둔다. */
    public <R> SliceResponse<R> map(Function<? super T, ? extends R> mapper) {
        return new SliceResponse<>(contents.stream().<R>map(mapper).toList(), hasNext);
    }
}
