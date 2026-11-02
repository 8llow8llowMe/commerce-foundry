package com.commercefoundry.domainlayer.knowledge.application.exception;

import lombok.Getter;

/** 지식 게시 정책의 불변식과 버전 계약을 적용한다. */
@Getter
public class KnowledgeException extends RuntimeException {

    private final KnowledgeErrorCode errorCode;

    public KnowledgeException(KnowledgeErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public KnowledgeException(KnowledgeErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public KnowledgeException(KnowledgeErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
