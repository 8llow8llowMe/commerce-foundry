package com.commercefoundry.messaging;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 메시징 사용 규칙 위반 — 호출한 코드의 실수다(트랜잭션 밖 발행 · 너무 큰 봉투 · 금지 필드 · 저장소 빈 없음). 메시지에 페이로드 값을 싣지 않는다.
 */
@Getter
public class MessagingException extends RuntimeException {

    private final Reason reason;

    public MessagingException(Reason reason) {
        super(reason.getMessage());
        this.reason = reason;
    }

    /** @param detail 설정 · 필드 이름처럼 값이 아닌 것만 */
    public MessagingException(Reason reason, String detail) {
        super(reason.getMessage() + " (" + detail + ")");
        this.reason = reason;
    }

    public MessagingException(Reason reason, Throwable cause) {
        super(reason.getMessage(), cause);
        this.reason = reason;
    }

    @Getter
    @RequiredArgsConstructor
    public enum Reason {
        /** outbox 저장은 업무 트랜잭션 안에서만 한다 — 밖에서 부르면 업무가 롤백돼도 이벤트가 나간다. */
        NO_TRANSACTION("이벤트 발행은 트랜잭션 안에서 해야 합니다."),
        /** 읽기 전용 트랜잭션은 flush 하지 않아 outbox 행이 저장되지 않는다. */
        READ_ONLY_TRANSACTION("이벤트 발행은 쓰기 트랜잭션 안에서 해야 합니다."),
        PAYLOAD_TOO_LARGE("이벤트 봉투가 상한을 넘습니다. 큰 데이터는 아이디만 싣고 컨슈머가 읽게 하세요."),
        /** 페이로드에 개인정보 · 비밀정보로 보이는 필드 이름이 있다 (#304 A-5). 메시지에는 필드 이름만 싣는다. */
        FORBIDDEN_FIELD("이벤트 페이로드에 실을 수 없는 필드입니다. 개인정보 · PG 키는 싣지 말고 아이디만 실으세요."),
        /** 이 서비스가 outbox · inbox 저장소 빈을 두지 않았다 (README). */
        STORE_MISSING("메시징 저장소 빈(OutboxStore · ProcessedEventStore)이 없습니다.");

        private final String message;
    }
}
