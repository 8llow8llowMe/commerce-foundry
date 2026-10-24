package com.commercefoundry.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RedactedThrowableTest {

    private static final String SECRET = "Key (email)=(member@example.com) already exists";

    @Test
    @DisplayName("메시지를 버리고 원래 타입을 메시지 자리에 둔다 — 스택 프레임은 원래 예외와 같다")
    void keepsTypeAndFramesOnly() {
        IllegalStateException original = new IllegalStateException(SECRET);

        RedactedThrowable redacted = RedactedThrowable.of(original);

        assertThat(redacted.getMessage()).isEqualTo(IllegalStateException.class.getName());
        assertThat(redacted.getStackTrace()).containsExactly(original.getStackTrace());
        assertThat(redacted.getCause()).isNull();
    }

    @Test
    @DisplayName("원인 사슬 · 억제된 예외도 같은 방식으로 바꾼다 — 어디에도 원래 메시지가 없다")
    void redactsCauseChainAndSuppressed() {
        IllegalStateException original = new IllegalStateException("outer " + SECRET, new IllegalArgumentException("inner " + SECRET));
        original.addSuppressed(new UnsupportedOperationException("suppressed " + SECRET));

        RedactedThrowable redacted = RedactedThrowable.of(original);

        assertThat(redacted.getCause()).isInstanceOf(RedactedThrowable.class)
            .hasMessage(IllegalArgumentException.class.getName());
        assertThat(redacted.getCause().getStackTrace()).containsExactly(original.getCause().getStackTrace());
        assertThat(redacted.getSuppressed()).singleElement().extracting(Throwable::getMessage)
            .isEqualTo(UnsupportedOperationException.class.getName());
        assertThat(redacted.toString() + redacted.getCause() + redacted.getSuppressed()[0]).doesNotContain("member@example.com");
    }

    @Test
    @DisplayName("SQLException 은 SQLState 를 덧붙인다 — 값이 아닌 표준 코드라 DB 오류 종류를 가릴 수 있다")
    void appendsSqlState() {
        RedactedThrowable redacted = RedactedThrowable.of(new SQLException("ERROR: duplicate key. Detail: " + SECRET, "23505"));

        assertThat(redacted.getMessage()).isEqualTo(SQLException.class.getName() + " [sqlState=23505]");
    }

    @Test
    @DisplayName("표준 모양(5자리 숫자 · 대문자)이 아닌 SQLState 는 싣지 않는다 — 생성자 인자라 아무 문자열이나 들어올 수 있다")
    void dropsNonStandardSqlState() {
        RedactedThrowable redacted = RedactedThrowable.of(new SQLException(SECRET, "member@example.com"));

        assertThat(redacted.getMessage()).isEqualTo(SQLException.class.getName());
    }

    @Test
    @DisplayName("사본을 만들다 실패하면 타입만 담은 사본이다 — 오류 처리기의 로그 인자 만들기가 깨지지 않는다")
    void fallsBackToTypeOnlyOnFailure() {
        IllegalStateException broken = new IllegalStateException(SECRET) {
            @Override
            public StackTraceElement[] getStackTrace() {
                return null;
            }
        };

        RedactedThrowable redacted = RedactedThrowable.of(broken);

        assertThat(redacted.getMessage()).isEqualTo(broken.getClass().getName() + " [redaction failed]").doesNotContain("member@example.com");
        assertThat(redacted.getStackTrace()).isEmpty();
    }

    @Test
    @DisplayName("순환하는 원인 사슬도 한 번만 따라가고 끝난다")
    void stopsOnCycle() {
        IllegalStateException first = new IllegalStateException(SECRET);
        IllegalArgumentException second = new IllegalArgumentException(SECRET, first);
        first.initCause(second);

        RedactedThrowable redacted = RedactedThrowable.of(first);

        assertThat(redacted.getCause()).hasMessage(IllegalArgumentException.class.getName());
        assertThat(redacted.getCause().getCause()).isNull();
    }

    @Test
    @DisplayName("null 은 null 이다 — 원인이 없을 수 있는 자리에 그대로 넘긴다")
    void nullStaysNull() {
        assertThat(RedactedThrowable.of(null)).isNull();
    }
}
