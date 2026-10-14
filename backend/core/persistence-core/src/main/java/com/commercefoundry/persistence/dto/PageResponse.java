package com.commercefoundry.persistence.dto;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * 어드민 목록(페이지 번호·전체 건수가 필요한 표)용 ({@code api-design-guide.md} §4).
 *
 * @param page          0 부터 센다 (Spring {@code Pageable} 과 같다)
 * @param totalElements 자르기 전 전체 건수. {@code contents.size()} 가 아니다
 */
public record PageResponse<T>(List<T> contents, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    /** Presenter 가 항목을 응답 DTO 로 바꿀 때 쓴다. 페이지 정보는 그대로 둔다. */
    public <R> PageResponse<R> map(Function<? super T, ? extends R> mapper) {
        return new PageResponse<>(contents.stream().<R>map(mapper).toList(), page, size, totalElements, totalPages);
    }
}
