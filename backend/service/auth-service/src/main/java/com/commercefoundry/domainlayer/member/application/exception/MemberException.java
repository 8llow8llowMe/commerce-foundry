package com.commercefoundry.domainlayer.member.application.exception;

import lombok.Getter;

@Getter
public class MemberException extends RuntimeException {

    private final MemberErrorCode errorCode;

    public MemberException(MemberErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 메시지의 {@code %s} 를 채운다. */
    public MemberException(MemberErrorCode errorCode, Object... args) {
        super(errorCode.getMessage().formatted(args));
        this.errorCode = errorCode;
    }

    public MemberException(MemberErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
