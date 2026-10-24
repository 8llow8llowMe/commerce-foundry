package com.commercefoundry.domainlayer.migration.domain.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 이관 원본의 칸 하나가 이 엔진의 값 규칙을 어긴 이유 ({@code migration-guide.md} §5). 거절은 그 행을 이관하지 않고, 경고는 그 칸만 비우고 이관한다.
 * 리포트 · 로그의 검색 키라 한번 정한 이름을 바꾸지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum MigrationIssueCode {

    SOURCE_KEY_REQUIRED(Severity.REJECT),
    NAME_REQUIRED(Severity.REJECT),
    NAME_TOO_LONG(Severity.REJECT),
    NAME_INVALID_CHARACTER(Severity.REJECT),
    PRICE_REQUIRED(Severity.REJECT),
    PRICE_INVALID(Severity.REJECT),
    HS_CODE_INVALID(Severity.WARN),
    WEIGHT_INVALID(Severity.WARN),
    EMAIL_REQUIRED(Severity.REJECT),
    EMAIL_INVALID(Severity.REJECT);

    private final Severity severity;

    public boolean rejects() {
        return severity == Severity.REJECT;
    }

    public enum Severity {
        /** 그 행을 이관하지 않는다. */
        REJECT,
        /** 그 칸만 비우고 이관한다 — 이관 뒤 어드민이 채운다. */
        WARN
    }
}
