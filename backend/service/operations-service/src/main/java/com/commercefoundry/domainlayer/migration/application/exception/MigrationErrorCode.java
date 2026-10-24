package com.commercefoundry.domainlayer.migration.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 데이터 이관 오류 코드 ({@code coding-conventions.md} §8). 칸 하나의 값 규칙 위반은 오류가 아니라 이슈({@code MigrationIssueCode})다 —
 * 여기는 파일 자체를 읽을 수 없는 경우만이다. 어드민 등록 API(#19 이후 PR)가 생기면 응답 봉투로 번역한다.
 */
@Getter
@RequiredArgsConstructor
public enum MigrationErrorCode {

    /** 머리글이 없거나 같은 컬럼명이 둘 이상이거나 CSV 형식이 깨졌다. 셀 값은 메시지에 싣지 않는다. */
    SOURCE_UNREADABLE("MIGRATION_001", "이관 원본 파일을 읽을 수 없습니다.", HttpStatus.BAD_REQUEST),
    /** 설정한 필수 컬럼이 머리글에 없다 — 원본 형식이 바뀌었거나 컬럼 설정이 틀렸다. */
    SOURCE_COLUMN_MISSING("MIGRATION_002", "이관 원본에 필요한 컬럼이 없습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
