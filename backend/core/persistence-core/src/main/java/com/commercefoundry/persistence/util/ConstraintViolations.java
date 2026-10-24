package com.commercefoundry.persistence.util;

import java.util.Locale;
import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * 저장 예외가 어느 제약을 위반했는지 — 예외 메시지가 아니라 제약 이름으로 가린다 ({@code coding-conventions.md} §9-1). 유니크 키 위반을 도메인 오류(409)로 바꾸는 어댑터가 쓴다.
 *
 * <p>판정 규칙:
 * <ul>
 *   <li><b>서버 오류 필드 우선</b> — 원인 사슬 전체에서 PostgreSQL 서버 오류 필드({@code PSQLException#getServerErrorMessage().getConstraint()})를 먼저 찾는다.
 *       서버 필드가 없을 때만(H2 등) Hibernate 의 {@code ConstraintViolationException#getConstraintName()} 을 쓴다. 서버 필드가 있으면 Hibernate 이름은 보지 않는다.</li>
 *   <li><b>로케일 무관</b> — Hibernate 이름은 오류 메시지 문구에서 뽑아 서버의 {@code lc_messages}(한국어 등)에 따라 null 이 될 수 있지만, 서버 필드는 문구와 상관없이 실린다.</li>
 *   <li><b>정확 일치</b> — 소문자로 바꾸고 따옴표 · 스키마 접두사를 뗀 뒤 같은 이름인지 본다. 부분 일치는 하지 않는다 — {@code payment_pkey} 는 {@code payment_cancel_pkey} 가 아니다.</li>
 * </ul>
 *
 * <p>H2 의 인덱스 접미사는 허용하지 않는다. H2 는 서버 필드가 없고, Hibernate 는 메시지 조각 {@code auth.uk_member_email INDEX auth.uk_member_email_INDEX_B} 를
 * 이름으로 넘긴다(auth H2 컨텍스트 실측) — 마지막 점 뒤를 쓰면 {@code uk_member_email_index_b} 라 H2 에서는 유니크 위반이 판정에 걸리지 않고 원래 예외로 나간다. 운영과 Testcontainers(PostgreSQL)는 서버 필드라 영향이 없고 H2 는 컨텍스트 로딩에만 쓰므로,
 * 테스트 DB 의 이름 규칙을 운영 판정에 넣지 않는다. 제약 위반 변환은 Testcontainers 리포지토리 테스트가 본다.
 *
 * <p>PostgreSQL 드라이버 · Hibernate 는 이 모듈에서 컴파일에만 쓴다 — 드라이버는 서비스의 {@code runtimeOnly}, Hibernate 는 {@code spring-boot-starter-data-jpa} 가 런타임에 둔다.
 * persistence-core 를 쓰는 서비스는 postgresql 드라이버가 runtime 에 있어야 한다 — 없으면 제약 위반 시 {@code instanceof PSQLException} 에서 NoClassDefFoundError.
 */
public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    /** 위반한 제약이 {@code constraintName} 인가. 이름은 {@link #constraintNameOf} 하나로 정한 뒤 정확히 비교한다. */
    public static boolean violates(Throwable exception, String constraintName) {
        return normalize(constraintName).equals(constraintNameOf(exception));
    }

    /**
     * 위반한 제약 이름 — 원인 사슬 전체에서 서버 필드를 먼저 찾고, 없으면 Hibernate 이름을 쓴다. 소문자로 바꾼 뒤 따옴표 · 스키마 접두사를 뗀다.
     * 예상 밖 위반을 다시 던질 때 메시지에도 싣는다.
     *
     * @return 이름을 알 수 없으면 null
     */
    public static String constraintNameOf(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = next(cause)) {
            if (cause instanceof PSQLException psql && serverConstraintOf(psql) != null) {
                return normalize(serverConstraintOf(psql));
            }
        }
        for (Throwable cause = exception; cause != null; cause = next(cause)) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                return normalize(violation.getConstraintName());
            }
        }
        return null;
    }

    private static Throwable next(Throwable cause) {
        return cause.getCause() == cause ? null : cause.getCause();
    }

    private static String serverConstraintOf(PSQLException exception) {
        ServerErrorMessage serverError = exception.getServerErrorMessage();
        return serverError == null ? null : serverError.getConstraint();
    }

    private static String normalize(String actual) {
        String name = actual.toLowerCase(Locale.ROOT).replace("\"", "");
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
