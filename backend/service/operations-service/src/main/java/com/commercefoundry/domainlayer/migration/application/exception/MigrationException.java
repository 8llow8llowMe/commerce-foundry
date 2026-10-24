package com.commercefoundry.domainlayer.migration.application.exception;

import lombok.Getter;

/** 이관 원본 파일 단위 오류. 메시지에 셀 값을 싣지 않는다 — 원인 예외(파서)의 메시지도 값을 담을 수 있어 원인으로만 둔다. */
@Getter
public class MigrationException extends RuntimeException {

    private final MigrationErrorCode errorCode;

    public MigrationException(MigrationErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public MigrationException(MigrationErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
