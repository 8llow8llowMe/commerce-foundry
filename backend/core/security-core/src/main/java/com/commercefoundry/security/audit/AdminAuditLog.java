package com.commercefoundry.security.audit;

import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.ClassUtils;

/**
 * 관리자 감사 로그 한 줄 (#163) — 전용 로거 {@value #LOGGER_NAME} 에 INFO {@code key=value} 네 칸으로 남긴다. 형식의 정본은 {@code security-guide.md} §11-4 다.
 *
 * <pre>
 * action=CATEGORY_CREATE actorMemberId=4611686018427387904 target=category:12 result=SUCCESS
 * action=DISPLAY_SECTION_UPDATE actorMemberId=4611686018427387904 target=display-section:main-hero result=FAILURE
 * </pre>
 *
 * <ul>
 *   <li>칸은 언제나 {@code action · actorMemberId · target · result} 넷이고 순서도 같다 — 모르는 칸은 {@code -}. 로그 수집기가 이 모양으로 파싱하므로
 *       auth-service 의 {@code AdminAuditLogAdapter} 와 글자 하나 다르지 않다. errorCode · 실패 이유는 싣지 않는다.</li>
 *   <li>{@code action} 은 {@code <RESOURCE>_<VERB>} 대문자 — {@value #ACTION_REGEX} 가 아니면 {@link IllegalArgumentException}(호출 코드의 버그).</li>
 *   <li>{@code target} 은 {@link #target(String, long)} · {@link #target(String, String)} 로 만든다. {@code write} 는 대상 전체가 {@value #TARGET_REGEX} 가 아니면
 *       통째로 {@code -} 로 남기고 원문 없는 WARN 을 남긴다 — 이메일 · 이름 · 전화 원문, 주소, 비밀번호 · 토큰이 감사 줄로 새지 않게.</li>
 *   <li>트랜잭션 동기화가 켜져 있으면 줄을 바로 쓰지 않고 트랜잭션이 끝난 뒤({@code afterCompletion}) 쓴다 — <b>커밋됐고 {@code succeeded} 일 때만</b>
 *       {@code SUCCESS}, 롤백이면 {@code FAILURE}. 롤백된 쓰기가 {@code SUCCESS} 로 남지 않게 한다. 동기화가 꺼져 있으면 바로 쓴다.</li>
 * </ul>
 *
 * <p>spring-tx 는 {@code compileOnly} 다 — 트랜잭션을 쓰는 서블릿 서비스만 이 클래스를 부른다. spring-tx 가 없는 실행 환경(리액티브 게이트웨이)에서
 * 불리면 동기화 없이 바로 쓴다.
 */
public final class AdminAuditLog {

    public static final String LOGGER_NAME = "admin.audit";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILURE = "FAILURE";

    static final String ACTION_REGEX = "^[A-Z][A-Z0-9_]{0,63}$";

    private static final Logger AUDIT = LoggerFactory.getLogger(LOGGER_NAME);
    private static final Logger LOG = LoggerFactory.getLogger(AdminAuditLog.class);
    private static final String NONE = "-";
    private static final Pattern ACTION = Pattern.compile(ACTION_REGEX);
    private static final Pattern TARGET_PART = Pattern.compile("[a-z0-9-]+");
    /**
     * {@code write} 가 받는 대상 전체 모양 — {@code -}, {@code <type>:<key>}, 또는 조회 조건 {@code <type>?<k>=<v>&...}.
     * 조회 조건은 auth {@code MEMBER_SEARCH}({@code members?role=ALL&page=0&size=20}) · order {@code ORDER_SEARCH}
     * ({@code orders?status=PAID&from=2026-01-01T00:00:00Z&to=-&page=0&size=20}) 가 쓰는 문자만 연다 — {@code @} · 한글 · 공백 · {@code ;} 는 받지 않는다.
     */
    static final String TARGET_REGEX = "^[a-z0-9-]+(?::[a-z0-9-]+|\\?[A-Za-z0-9=&_:.-]*)?$";
    private static final Pattern TARGET = Pattern.compile(TARGET_REGEX);
    private static final boolean TX_PRESENT =
        ClassUtils.isPresent("org.springframework.transaction.support.TransactionSynchronizationManager", AdminAuditLog.class.getClassLoader());

    private AdminAuditLog() {
    }

    /**
     * 감사 한 줄을 남긴다. 트랜잭션 안이면 트랜잭션 결과에 따라 끝난 뒤 남긴다.
     *
     * @param action         {@code <RESOURCE>_<VERB>} — 형식이 아니면 {@link IllegalArgumentException}
     * @param actorMemberId  행위자 회원 ID — 모르면 {@code null}({@code -})
     * @param target         대상 — {@link #target} 으로 만든다. 없으면 {@code null}({@code -})
     * @param succeeded      업무 결과. 트랜잭션 안이면 커밋까지 되어야 {@code SUCCESS}
     */
    public static void write(String action, Long actorMemberId, String target, boolean succeeded) {
        requireAction(action);
        String safeTarget = sanitize(action, target);
        if (TX_PRESENT && Deferred.register(action, actorMemberId, safeTarget, succeeded)) {
            return;
        }
        emit(action, actorMemberId, safeTarget, succeeded);
    }

    /** {@code <type>:<id>} — 예: {@code category:12}. {@code type} 은 {@code [a-z0-9-]+}. */
    public static String target(String type, long id) {
        return requirePart("type", type) + ":" + id;
    }

    /** {@code <type>:<key>} — 예: {@code display-section:main-hero}. {@code type} · {@code key} 는 {@code [a-z0-9-]+} 가 아니면 {@link IllegalArgumentException}. */
    public static String target(String type, String key) {
        return requirePart("type", type) + ":" + requirePart("key", key);
    }

    static void emit(String action, Long actorMemberId, String target, boolean succeeded) {
        AUDIT.info("action={} actorMemberId={} target={} result={}", action, orNone(actorMemberId), orNone(target), succeeded ? SUCCESS : FAILURE);
    }

    /** 예외 메시지에 원문을 싣지 않는다 — 호출 코드가 잘못 넘긴 값이 개인정보일 수 있다. 길이만 남긴다. */
    private static void requireAction(String action) {
        if (action == null || !ACTION.matcher(action).matches()) {
            throw new IllegalArgumentException("감사 action 은 " + ACTION_REGEX + " 형식이어야 합니다 (길이 " + lengthOf(action) + ")");
        }
    }

    private static String requirePart(String name, String value) {
        if (value == null || !TARGET_PART.matcher(value).matches()) {
            throw new IllegalArgumentException("감사 target 의 " + name + " 은 [a-z0-9-]+ 형식이어야 합니다 (길이 " + lengthOf(value) + ")");
        }
        return value;
    }

    /**
     * 대상 전체가 {@value #TARGET_REGEX} 가 아니면 통째로 {@code -} 로 바꾼다 — 일부만 고치면 이메일 · 이름 같은 원문이 남는다.
     * WARN 에도 원문을 싣지 않는다(행위 · 길이만).
     */
    private static String sanitize(String action, String target) {
        if (target == null || TARGET.matcher(target).matches()) {
            return target;
        }
        LOG.warn("감사 target 이 형식(<type>:<key> 또는 <type>?<조건>)에 맞지 않아 '-' 로 남깁니다 — AdminAuditLog.target(...) 을 쓰세요 (action={}, 길이 {})",
            action, target.length());
        return NONE;
    }

    private static String lengthOf(String value) {
        return value == null ? "null" : String.valueOf(value.length());
    }

    private static String orNone(Object value) {
        return value == null ? NONE : String.valueOf(value);
    }

    /** spring-tx 에 기대는 부분. 바깥 클래스가 spring-tx 없이도 로드되도록 따로 둔다. */
    private static final class Deferred {

        private Deferred() {
        }

        /** @return 동기화에 걸었으면 {@code true} — 이때 줄은 트랜잭션이 끝난 뒤 쓴다 */
        static boolean register(String action, Long actorMemberId, String target, boolean succeeded) {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                return false;
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    emit(action, actorMemberId, target, succeeded && status == STATUS_COMMITTED);
                }
            });
            return true;
        }
    }
}
