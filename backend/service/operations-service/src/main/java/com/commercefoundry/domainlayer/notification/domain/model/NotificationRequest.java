package com.commercefoundry.domainlayer.notification.domain.model;

import java.util.Objects;

/**
 * 알림 하나 만들기 입력. 다른 컨텍스트 Facade 는 {@code NotificationProcessor.record} 에 이 값만 넘긴다 (#213).
 *
 * <p>제목 · 링크에 고객 개인정보 원문을 넣지 않는다. 링크는 어드민 화면 경로({@code /} 로 시작)이거나 null 이다.
 */
public record NotificationRequest(
    NotificationKind kind,
    NotificationSeverity severity,
    String title,
    String linkPath,
    long recipientMemberId,
    String sourceType,
    long sourceId
) {

    public static final int TITLE_MAX_LENGTH = 120;
    public static final int LINK_MAX_LENGTH = 200;
    public static final int SOURCE_TYPE_MAX_LENGTH = 32;

    public NotificationRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(sourceType, "sourceType");
        if (title.isBlank() || title.length() > TITLE_MAX_LENGTH || title.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("title");
        }
        if (linkPath != null && !isScreenPath(linkPath)) {
            throw new IllegalArgumentException("linkPath");
        }
        if (!sourceType.matches("[A-Z0-9_]{1,%d}".formatted(SOURCE_TYPE_MAX_LENGTH))) {
            throw new IllegalArgumentException("sourceType");
        }
        if (recipientMemberId <= 0 || sourceId <= 0) {
            throw new IllegalArgumentException("id");
        }
    }

    private static boolean isScreenPath(String linkPath) {
        return !linkPath.isBlank()
            && linkPath.length() <= LINK_MAX_LENGTH
            && linkPath.startsWith("/")
            && linkPath.indexOf(' ') < 0
            && linkPath.codePoints().noneMatch(Character::isISOControl);
    }
}
