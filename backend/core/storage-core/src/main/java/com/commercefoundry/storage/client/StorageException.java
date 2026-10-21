package com.commercefoundry.storage.client;

/**
 * 스토리지 호출 실패. 서비스 어댑터가 {@link #reason()} 으로 도메인 오류 코드를 고른다(예: {@code UNAVAILABLE} · {@code DISABLED} → 503).
 *
 * <p>메시지는 동작과 원인 구분만 담는다 — 키 · 자격 증명 · 서명 URL 을 넣지 않는다. 진단은 원인 예외(SDK 의 상태 코드 · 오류 코드 · 요청 ID)로 한다.
 */
public class StorageException extends RuntimeException {

    /** 실패 원인 구분. */
    public enum Reason {

        /** {@code storage.enabled=false} — 설정으로 꺼 두었다. */
        DISABLED("스토리지가 꺼져 있습니다 (storage.enabled=false)"),
        /** 네트워크 · 타임아웃 · 5xx · 요청 제한(429) — 다시 시도하면 될 수 있다. */
        UNAVAILABLE("스토리지에 닿지 못했거나 스토리지가 일시적으로 응답하지 못했습니다"),
        /** 401 · 403 — 키 · 권한 · 서명 문제다. 다시 시도해도 풀리지 않는다. */
        ACCESS_DENIED("스토리지가 접근을 거부했습니다 (키 · 권한 · 서명)"),
        /** 읽거나 복사할 객체가 없다. */
        NOT_FOUND("객체가 없습니다"),
        /**
         * 412 — 객체가 확인({@code head}) 뒤 바뀌었다. {@code readRange} · {@code copy} 의 {@code expectedETag} 가 지금 ETag 와 다르다.
         * 같은 키에 다른 내용이 다시 올라온 경우라 확인 결과를 믿을 수 없다 — 서비스는 다시 확인하라는 4xx(400 · 409)로 번역한다.
         */
        CHANGED("객체가 확인한 뒤 바뀌었습니다 (ETag 불일치)"),
        /** 그 밖의 4xx — 요청이나 설정(버킷 등)이 스토리지 규칙에 맞지 않는다. */
        REJECTED("스토리지가 요청을 거절했습니다");

        private final String description;

        Reason(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    /** 실패한 동작 — 메시지 · 로그에서 어느 호출인지 가린다. */
    public enum Operation {
        PRESIGN_PUT, HEAD, READ_RANGE, COPY, DELETE, PUT, PUBLIC_URL
    }

    private final Reason reason;
    private final Operation operation;

    public StorageException(Reason reason, Operation operation) {
        this(reason, operation, null);
    }

    public StorageException(Reason reason, Operation operation, Throwable cause) {
        super("storage %s failed: %s — %s".formatted(operation, reason, reason.description()), cause);
        this.reason = reason;
        this.operation = operation;
    }

    public Reason reason() {
        return reason;
    }

    public Operation operation() {
        return operation;
    }
}
