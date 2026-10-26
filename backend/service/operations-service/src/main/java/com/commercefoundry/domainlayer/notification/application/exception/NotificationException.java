package com.commercefoundry.domainlayer.notification.application.exception;

import lombok.Getter;

@Getter
public class NotificationException extends RuntimeException {

    private final NotificationErrorCode errorCode;

    public NotificationException(NotificationErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
