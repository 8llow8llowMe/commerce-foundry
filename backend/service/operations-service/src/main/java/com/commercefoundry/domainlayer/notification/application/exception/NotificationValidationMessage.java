package com.commercefoundry.domainlayer.notification.application.exception;

/**
 * 어드민 알림 API 요청 검증 메시지 — {@code "<코드>:<문구>"} ({@code coding-conventions.md} §8). 대역은 {@code NOTIFICATION_1xx} 다.
 */
public final class NotificationValidationMessage {

    public static final String PAGE_MIN = "NOTIFICATION_101:page 는 0 이상이어야 합니다.";
    public static final String SIZE_RANGE = "NOTIFICATION_102:size 는 1 이상 100 이하여야 합니다.";
    public static final String NOTIFICATION_ID_POSITIVE = "NOTIFICATION_103:notificationId 는 양수여야 합니다.";

    private NotificationValidationMessage() {
    }
}
