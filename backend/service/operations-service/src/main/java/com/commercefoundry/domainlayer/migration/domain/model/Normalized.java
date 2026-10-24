package com.commercefoundry.domainlayer.migration.domain.model;

/**
 * 칸 하나의 정규화 결과 — 값이거나, 비운 칸이거나, 이유 코드다. 예외를 던지지 않고 결과로 돌려 행 전체의 이슈를 한 번에 모은다.
 *
 * @param value 정규화한 값. 비었거나 실패면 null
 * @param issue 실패 이유. 성공 · 빈 칸이면 null
 */
public record Normalized<T>(T value, MigrationIssueCode issue) {

    public static <T> Normalized<T> of(T value) {
        return new Normalized<>(value, null);
    }

    public static <T> Normalized<T> empty() {
        return new Normalized<>(null, null);
    }

    public static <T> Normalized<T> failed(MigrationIssueCode issue) {
        return new Normalized<>(null, issue);
    }

    public boolean failed() {
        return issue != null;
    }
}
