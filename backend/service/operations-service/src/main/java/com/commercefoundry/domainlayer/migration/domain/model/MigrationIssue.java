package com.commercefoundry.domainlayer.migration.domain.model;

import java.util.Objects;

/**
 * 원본 한 행 · 한 칸의 정규화 실패. <b>셀 값을 싣지 않는다</b> — 행 번호 · 원본 키 · 칸 이름 · 사유 코드만 ({@code migration-guide.md} §6).
 *
 * @param rowNumber 원본 데이터 행 번호(머리글 다음 행이 1)
 * @param sourceKey 원본 업무 키(상품코드). 회원 행이나 키를 읽지 못한 행이면 null
 * @param field 칸 이름 — 이 엔진의 필드 이름(name · salePrice …)이다. 원본 컬럼명이 아니다
 */
public record MigrationIssue(int rowNumber, String sourceKey, String field, MigrationIssueCode code) {

    public MigrationIssue {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(code, "code");
    }
}
