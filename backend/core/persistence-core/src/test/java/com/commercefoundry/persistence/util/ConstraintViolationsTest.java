package com.commercefoundry.persistence.util;

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

    /** 서버 필드가 없는 Hibernate 제약 위반(H2 등) — Hibernate 가 메시지에서 뽑은 이름만 있다. */
    private static DataIntegrityViolationException hibernate(String constraintName) {
        return new DataIntegrityViolationException("x", new ConstraintViolationException("x", new SQLException("x"), constraintName));
    }

    @Test
    @DisplayName("PostgreSQL 서버 오류 필드의 제약 이름을 먼저 본다 — 메시지가 한국어여서 Hibernate 가 이름을 못 뽑아도(null) 가린다")
    void serverFieldWinsOverLocalizedMessage() {
        PSQLException server = psql("중복된 키 값이 \"uk_x\" 고유 제약 조건을 위반함", "uk_order_request_member_id_idempotency_key");
        DataIntegrityViolationException exception = new DataIntegrityViolationException("x", new ConstraintViolationException("x", server, null));

        assertThat(ConstraintViolations.violates(exception, "uk_order_request_member_id_idempotency_key")).isTrue();
        assertThat(ConstraintViolations.constraintNameOf(exception)).isEqualTo("uk_order_request_member_id_idempotency_key");
    }

    @Test
    @DisplayName("서버 필드가 있으면 Hibernate 가 뽑은 이름은 보지 않는다 — 판정 · 메시지 이름 모두 서버 필드다")
    void serverFieldWinsOverHibernateName() {
        PSQLException server = psql("x", "uk_payment_payment_key");
        DataIntegrityViolationException exception = new DataIntegrityViolationException("x", new ConstraintViolationException("x", server, "something_else"));

        assertThat(ConstraintViolations.constraintNameOf(exception)).isEqualTo("uk_payment_payment_key");
        assertThat(ConstraintViolations.violates(exception, "uk_payment_payment_key")).isTrue();
        assertThat(ConstraintViolations.violates(exception, "something_else")).isFalse();
    }

    @Test
    @DisplayName("서버 필드가 원인 사슬 안쪽에 있어도 찾는다 — 바깥의 Hibernate 이름보다 먼저다")
    void searchesWholeCauseChain() {
        PSQLException server = psql("x", "uk_member_email");
        RuntimeException wrapped = new RuntimeException("outer", new IllegalStateException("middle", server));
        DataIntegrityViolationException exception = new DataIntegrityViolationException("x", new ConstraintViolationException("x", new SQLException("x", wrapped), "other_name"));

        assertThat(ConstraintViolations.constraintNameOf(exception)).isEqualTo("uk_member_email");
        assertThat(ConstraintViolations.violates(exception, "uk_member_email")).isTrue();
        assertThat(ConstraintViolations.violates(exception, "other_name")).isFalse();
    }

    @Test
    @DisplayName("서버 오류 필드가 비어 있는 PSQLException 은 건너뛰고 Hibernate 이름을 쓴다")
    void psqlWithoutServerFieldFallsBack() {
        DataIntegrityViolationException exception = new DataIntegrityViolationException(
            "x", new ConstraintViolationException("x", new PSQLException(new ServerErrorMessage("SERROR\0C23505\0Mx\0")), "uk_shipping_address_default_member_id")
        );

        assertThat(ConstraintViolations.constraintNameOf(exception)).isEqualTo("uk_shipping_address_default_member_id");
    }

    @Test
    @DisplayName("서버 필드가 없으면(H2 등) Hibernate 가 준 이름을 쓴다 — 대소문자 · 스키마 접두사 · 따옴표는 무시한다")
    void fallsBackToHibernateName() {
        DataIntegrityViolationException exception = hibernate("PAYMENT.\"UK_PAYMENT_ACTIVE_ORDER_ID\"");

        assertThat(ConstraintViolations.violates(exception, "uk_payment_active_order_id")).isTrue();
        assertThat(ConstraintViolations.violates(exception, "UK_PAYMENT_ACTIVE_ORDER_ID")).as("기대 이름의 대소문자도 무시한다").isTrue();
        assertThat(ConstraintViolations.violates(exception, "payment_pkey")).isFalse();
        assertThat(ConstraintViolations.constraintNameOf(exception)).isEqualTo("uk_payment_active_order_id");
    }

    @Test
    @DisplayName("서버 필드의 따옴표 · 스키마 접두사 · 대문자도 같은 규칙으로 뗀다")
    void normalizesServerField() {
        assertThat(ConstraintViolations.constraintNameOf(psql("x", "orders.\"UK_ORDERS_STOCK_RESERVATION_ID\""))).isEqualTo("uk_orders_stock_reservation_id");
    }

    @Test
    @DisplayName("부분 일치는 다른 제약이다 — 앞부분 · 뒷부분만 같거나 다른 테이블의 PK 면 아니다")
    void exactNameOnly() {
        assertThat(ConstraintViolations.violates(psql("x", "uk_payment_active_order_id"), "uk_payment_active")).as("앞부분").isFalse();
        assertThat(ConstraintViolations.violates(psql("x", "payment_cancel_pkey"), "payment_pkey")).as("다른 테이블의 PK").isFalse();
        assertThat(ConstraintViolations.violates(psql("x", "order_item_pkey"), "orders_pkey")).isFalse();
        assertThat(ConstraintViolations.violates(psql("x", "orders_pkey"), "orders_pkey")).isTrue();
        assertThat(ConstraintViolations.violates(hibernate("uk_member_email_extra"), "uk_member_email")).as("Hibernate 이름도 정확히 비교한다").isFalse();
    }

    @Test
    @DisplayName("H2 인덱스 접미사는 허용하지 않는다 — H2 는 메시지 조각(auth H2 실측 값)을 이름으로 넘긴다. 운영 · Testcontainers 는 서버 필드라 영향이 없다")
    void h2IndexSuffixIsNotMatched() {
        DataIntegrityViolationException h2 = hibernate("auth.uk_member_email INDEX auth.uk_member_email_INDEX_B");

        assertThat(ConstraintViolations.violates(h2, "uk_member_email")).isFalse();
        assertThat(ConstraintViolations.constraintNameOf(h2)).as("마지막 점 뒤를 이름으로 알려 준다 — 예상 밖 위반 메시지에 싣는다").isEqualTo("uk_member_email_index_b");
    }

    @Test
    @DisplayName("이름 없는 위반 — 판정은 false, 이름은 null")
    void noConstraintName() {
        assertThat(ConstraintViolations.violates(new DataIntegrityViolationException("x"), "payment_pkey")).isFalse();
        assertThat(ConstraintViolations.constraintNameOf(new DataIntegrityViolationException("x"))).isNull();
        assertThat(ConstraintViolations.constraintNameOf(hibernate(null))).isNull();
    }
}
