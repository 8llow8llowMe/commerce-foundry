package com.commercefoundry.global.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

class ConstraintViolationsTest {

    /** PostgreSQL 서버 오류 필드 — 'C' SQLSTATE, 'M' 메시지(로케일에 따라 바뀐다), 'n' 제약 이름. 필드는 NUL 로 나뉜다. */
    private static PSQLException psql(String localizedMessage, String constraint) {
        return new PSQLException(new ServerErrorMessage("SERROR\0C23505\0M" + localizedMessage + "\0n" + constraint + "\0"));
    }

    @Test
    @DisplayName("PostgreSQL 서버 오류 필드의 제약 이름을 먼저 본다 — 메시지가 한국어여서 Hibernate 가 이름을 못 뽑아도(null) 가린다")
    void serverFieldWinsOverLocalizedMessage() {
        PSQLException server = psql("중복된 키 값이 \"uk_x\" 고유 제약 조건을 위반함", "uk_payment_active_order_id");
        DataIntegrityViolationException exception = new DataIntegrityViolationException("x", new ConstraintViolationException("x", server, null));

        assertThat(ConstraintViolations.violates(exception, "uk_payment_active_order_id")).isTrue();
        assertThat(ConstraintViolations.violates(exception, "uk_payment_active")).as("앞부분만 같은 이름은 다른 제약이다").isFalse();
        assertThat(ConstraintViolations.constraintNameOf(exception)).isEqualTo("uk_payment_active_order_id");
    }

    @Test
    @DisplayName("서버 필드가 있으면 Hibernate 가 뽑은 이름보다 서버 필드의 이름을 싣는다")
    void constraintNamePrefersServerField() {
        PSQLException server = psql("x", "uk_payment_payment_key");
        DataIntegrityViolationException exception = new DataIntegrityViolationException("x", new ConstraintViolationException("x", server, "something_else"));

        assertThat(ConstraintViolations.constraintNameOf(exception)).isEqualTo("uk_payment_payment_key");
    }

    @Test
    @DisplayName("서버 필드가 없으면(H2 등) Hibernate 가 준 이름을 쓴다 — 대소문자 · 스키마 접두사 · 따옴표는 무시한다")
    void fallsBackToHibernateName() {
        ConstraintViolationException hibernate = new ConstraintViolationException("x", new SQLException("x"), "PAYMENT.\"UK_PAYMENT_ACTIVE_ORDER_ID\"");
        DataIntegrityViolationException exception = new DataIntegrityViolationException("x", hibernate);

        assertThat(ConstraintViolations.violates(exception, "uk_payment_active_order_id")).isTrue();
        assertThat(ConstraintViolations.violates(exception, "payment_pkey")).isFalse();
        assertThat(ConstraintViolations.constraintNameOf(exception)).isEqualTo("uk_payment_active_order_id");
        assertThat(ConstraintViolations.violates(new DataIntegrityViolationException("x"), "payment_pkey")).as("이름 없는 위반").isFalse();
        assertThat(ConstraintViolations.constraintNameOf(new DataIntegrityViolationException("x"))).as("이름 없는 위반").isNull();
    }

    @Test
    @DisplayName("다른 테이블의 PK 는 결제 PK 가 아니다 — payment_pkey 와 payment_cancel_pkey 를 가린다")
    void exactName() {
        assertThat(ConstraintViolations.violates(psql("x", "payment_cancel_pkey"), "payment_pkey")).isFalse();
        assertThat(ConstraintViolations.violates(psql("x", "payment_pkey"), "payment_pkey")).isTrue();
    }
}
