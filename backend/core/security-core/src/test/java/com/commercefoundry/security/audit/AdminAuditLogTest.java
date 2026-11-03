package com.commercefoundry.security.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 감사 한 줄의 형식과 트랜잭션 결과 반영 ({@code security-guide.md} §11-4). 형식은 로그 수집기의 파서 계약이라 auth-service 의
 * {@code AdminAuditLogAdapterTest} 와 같은 문자열을 단언한다 — 칸 순서 · 이름을 바꾸면 이 테스트가 깨져야 한다.
 */
class AdminAuditLogTest {

    private ListAppender<ILoggingEvent> audit;
    private ListAppender<ILoggingEvent> warnings;

    @BeforeEach
    void attachAppenders() {
        audit = attach(AdminAuditLog.LOGGER_NAME);
        warnings = attach(AdminAuditLog.class.getName());
    }

    @AfterEach
    void detachAppenders() {
        ((Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME)).detachAppender(audit);
        ((Logger) LoggerFactory.getLogger(AdminAuditLog.class.getName())).detachAppender(warnings);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.clear();
    }

    @Test
    @DisplayName("트랜잭션 밖이면 바로 admin.audit 에 INFO 네 칸(action · actorMemberId · target · result)으로 남긴다")
    void writesFourFieldsInOrder() {
        AdminAuditLog.write("CATEGORY_CREATE", 9L, AdminAuditLog.target("category", 12L), true);
        AdminAuditLog.write("DISPLAY_SECTION_UPDATE", 9L, AdminAuditLog.target("display-section", "main-hero"), false);

        assertThat(audit.list).extracting(ILoggingEvent::getLevel).containsOnly(Level.INFO);
        assertThat(lines()).containsExactly(
            "action=CATEGORY_CREATE actorMemberId=9 target=category:12 result=SUCCESS",
            "action=DISPLAY_SECTION_UPDATE actorMemberId=9 target=display-section:main-hero result=FAILURE"
        );
    }

    @Test
    @DisplayName("행위자 · 대상이 없으면 '-' 다 — 칸 수는 언제나 넷")
    void nullFieldsBecomeDash() {
        AdminAuditLog.write("ADMIN_LOGIN", null, null, false);

        assertThat(lines()).containsExactly("action=ADMIN_LOGIN actorMemberId=- target=- result=FAILURE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "category_create", "1CATEGORY", "_CATEGORY", "CATEGORY CREATE", "CATEGORY-CREATE", "CATEGORY_CREATE\n"})
    @DisplayName("행위 이름이 ^[A-Z][A-Z0-9_]{0,63}$ 가 아니면 IllegalArgumentException — 줄을 남기지 않는다")
    void rejectsMalformedAction(String action) {
        assertThatThrownBy(() -> AdminAuditLog.write(action, 9L, null, true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(audit.list).isEmpty();
    }

    @Test
    @DisplayName("행위 이름이 null 이거나 65자면 IllegalArgumentException, 64자는 받는다 — 상한 경계")
    void actionLengthBoundary() {
        String longest = "A" + "B".repeat(63);

        assertThatThrownBy(() -> AdminAuditLog.write(null, 9L, null, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdminAuditLog.write(longest + "C", 9L, null, true)).isInstanceOf(IllegalArgumentException.class);
        AdminAuditLog.write(longest, 9L, null, true);

        assertThat(lines()).containsExactly("action=" + longest + " actorMemberId=9 target=- result=SUCCESS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Main", "main hero", "main_hero", "main:hero", "홍길동", "a@b.com"})
    @DisplayName("target 의 key · type 이 [a-z0-9-]+ 가 아니면 IllegalArgumentException")
    void rejectsMalformedTargetPart(String value) {
        assertThatThrownBy(() -> AdminAuditLog.target("display-section", value)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdminAuditLog.target(value, 1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("target 의 key · type 이 null 이면 IllegalArgumentException")
    void rejectsNullTargetPart() {
        assertThatThrownBy(() -> AdminAuditLog.target("display-section", (String) null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdminAuditLog.target(null, 1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("형식 오류의 IllegalArgumentException 메시지에는 원문이 없다 — 이름 · 길이만")
    void exceptionMessagesOmitRawValue() {
        String email = "kim@example.com";

        assertThatThrownBy(() -> AdminAuditLog.write(email, 9L, null, true)).hasMessageNotContaining(email).hasMessageContaining("길이 15");
        assertThatThrownBy(() -> AdminAuditLog.target("member", email)).hasMessageNotContaining(email).hasMessageContaining("key");
        assertThatThrownBy(() -> AdminAuditLog.target("홍길동", 1L)).hasMessageNotContaining("홍길동").hasMessageContaining("type");
    }

    /** 일부만 고치면 원문(이메일 · 이름)이 남으므로 통째로 '-' 다. 서식 문자(U+202E 방향 바꿈 · U+200B 폭 없는 공백)도 같다. */
    @ParameterizedTest
    @ValueSource(strings = {
        "policy:a b\nresult=SUCCESS\tx", "member:kim@example.com", "member:홍길동", "member:‮moc.elpmaxe", "member:12​3", "x;result=SUCCESS",
        "members?q=kim@example.com", "members?name=홍길동", "Category:1", "category:1 result=SUCCESS", "category:", ":1", "category:1:2"
    })
    @DisplayName("형식(<type>:<key> · <type>?<조건>)에 맞지 않는 target 은 통째로 '-' 이고 WARN 에는 원문이 없다")
    void replacesMalformedTargetWhole(String target) {
        AdminAuditLog.write("POLICY_UPDATE", 9L, target, true);

        assertThat(lines()).containsExactly("action=POLICY_UPDATE actorMemberId=9 target=- result=SUCCESS");
        assertThat(warnings.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("POLICY_UPDATE", "길이 " + target.length()).doesNotContain(target);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "-", "category:12", "display-section:main-hero", "order:-1", "members?role=ALL&page=0&size=20",
        "orders?status=PAID&from=2026-01-01T00:00:00Z&to=-&page=0&size=20", "orders?status=ALL&from=2026-01-01T00:00:00.123Z&to=-&page=0&size=20"
    })
    @DisplayName("형식에 맞는 target 은 그대로 둔다 — auth MEMBER_SEARCH · order ORDER_SEARCH 조회 조건(Instant 포함)도 바꾸지 않고 WARN 도 없다")
    void keepsWellFormedTarget(String target) {
        AdminAuditLog.write("MEMBER_SEARCH", 9L, target, true);

        assertThat(lines()).containsExactly("action=MEMBER_SEARCH actorMemberId=9 target=" + target + " result=SUCCESS");
        assertThat(warnings.list).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("실제 트랜잭션 없는 빈 동기화에서는 업무 결과를 즉시 기록하고 완료 콜백을 등록하지 않는다")
    void emptySynchronizationWritesImmediately(boolean succeeded) {
        TransactionSynchronizationManager.initSynchronization();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        AdminAuditLog.write("ORDER_STATUS_CHANGE", 9L, "order:12", succeeded);

        assertThat(lines()).containsExactly("action=ORDER_STATUS_CHANGE actorMemberId=9 target=order:12 result=" + (succeeded ? "SUCCESS" : "FAILURE"));
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    @DisplayName("실제 Spring NEVER 프록시의 응답 실패는 이미 확정된 업무 SUCCESS 를 FAILURE 로 바꾸지 않는다")
    void neverProxyDoesNotReverseSuccessfulAudit() {
        AuditedWork work = proxiedWork(new AuditTransactionManager());

        assertThatThrownBy(work::never).isInstanceOf(IllegalStateException.class).hasMessage("response unavailable");

        assertThat(lines()).containsExactly("action=ORDER_STATUS_CHANGE actorMemberId=9 target=order:12 result=SUCCESS");
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    @Test
    @DisplayName("실제 Spring SUPPORTS 프록시도 실제 트랜잭션이 없으면 후속 예외와 무관하게 SUCCESS 를 보존한다")
    void supportsProxyDoesNotReverseSuccessfulAudit() {
        AuditedWork work = proxiedWork(new AuditTransactionManager());

        assertThatThrownBy(work::supports).isInstanceOf(IllegalStateException.class).hasMessage("response unavailable");

        assertThat(lines()).containsExactly("action=ORDER_STATUS_CHANGE actorMemberId=9 target=order:12 result=SUCCESS");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("실제 Spring REQUIRED 트랜잭션은 완료까지 미루고 커밋 SUCCESS · 롤백 FAILURE 를 남긴다")
    void realTransactionUsesCompletionResult(boolean rollback) {
        AuditedWork work = proxiedWork(new AuditTransactionManager());
        if (rollback) {
            assertThatThrownBy(() -> work.required(true)).isInstanceOf(IllegalStateException.class).hasMessage("response unavailable");
        } else {
            work.required(false);
        }

        assertThat(lines()).containsExactly("action=ORDER_STATUS_CHANGE actorMemberId=9 target=order:12 result=" + (rollback ? "FAILURE" : "SUCCESS"));
    }

    @Test
    @DisplayName("실제 트랜잭션 매니저의 커밋 결과 불명은 FAILURE 를 남긴다")
    void realTransactionWithUnknownCommitIsFailure() {
        AuditTransactionManager manager = new AuditTransactionManager();
        manager.failCommit = true;
        AuditedWork work = proxiedWork(manager);

        assertThatThrownBy(() -> work.required(false)).isInstanceOf(TransactionSystemException.class);

        assertThat(lines()).containsExactly("action=ORDER_STATUS_CHANGE actorMemberId=9 target=order:12 result=FAILURE");
    }

    @Test
    @DisplayName("afterCompletion 중 다시 쓰는 감사는 동기화가 끝났으므로 즉시 한 번 기록한다")
    void writesFromAfterCompletionWithoutRegisteringAgain() {
        new TransactionTemplate(new AuditTransactionManager()).executeWithoutResult(status -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int completionStatus) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                    AdminAuditLog.write("JOB_CANCEL", 9L, "job:13", true);
                }
            });
            AdminAuditLog.write("ORDER_STATUS_CHANGE", 9L, "order:12", true);
            assertThat(audit.list).isEmpty();
        });

        assertThat(lines()).containsExactly(
            "action=JOB_CANCEL actorMemberId=9 target=job:13 result=SUCCESS",
            "action=ORDER_STATUS_CHANGE actorMemberId=9 target=order:12 result=SUCCESS"
        );
    }

    private AuditedWork proxiedWork(AuditTransactionManager manager) {
        ProxyFactory factory = new ProxyFactory(new AuditedWork(() -> assertThat(audit.list).isEmpty()));
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (AuditedWork) factory.getProxy();
    }

    /** 외부 리소스 없이 Spring 의 전파 · 동기화 · 완료 처리를 돌린다. 실제 DB 커밋 보존은 order 의 H2 테스트가 본다. */
    static class AuditTransactionManager extends AbstractPlatformTransactionManager {

        private boolean failCommit;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) {
                throw new TransactionSystemException("commit result unknown");
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    static class AuditedWork {

        private final Runnable assertDeferred;

        AuditedWork(Runnable assertDeferred) {
            this.assertDeferred = assertDeferred;
        }

        @Transactional(propagation = Propagation.NEVER)
        public void never() {
            nonTransactionalSuccessThenThrow();
        }

        @Transactional(propagation = Propagation.SUPPORTS)
        public void supports() {
            nonTransactionalSuccessThenThrow();
        }

        private void nonTransactionalSuccessThenThrow() {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
            AdminAuditLog.write("ORDER_STATUS_CHANGE", 9L, "order:12", true);
            throw new IllegalStateException("response unavailable");
        }

        @Transactional
        public void required(boolean rollback) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
            AdminAuditLog.write("ORDER_STATUS_CHANGE", 9L, "order:12", true);
            assertDeferred.run();
            if (rollback) {
                throw new IllegalStateException("response unavailable");
            }
        }
    }

    /** 실제 트랜잭션을 모델링한다 — 동기화와 actualTransactionActive 를 함께 켜고 완료 콜백을 부른다. */
    @Nested
    @DisplayName("트랜잭션 동기화")
    class Synchronized {

        @BeforeEach
        void init() {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.initSynchronization();
        }

        @AfterEach
        void clear() {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        @Test
        @DisplayName("커밋되면 SUCCESS — 커밋 전에는 줄이 없다")
        void committed() {
            AdminAuditLog.write("CATEGORY_CREATE", 9L, AdminAuditLog.target("category", 12L), true);

            assertThat(audit.list).isEmpty();
            complete(TransactionSynchronization.STATUS_COMMITTED);
            assertThat(lines()).containsExactly("action=CATEGORY_CREATE actorMemberId=9 target=category:12 result=SUCCESS");
        }

        @Test
        @DisplayName("롤백되면 succeeded 여도 FAILURE — 롤백된 쓰기가 SUCCESS 로 남지 않는다")
        void rolledBack() {
            AdminAuditLog.write("CATEGORY_CREATE", 9L, AdminAuditLog.target("category", 12L), true);

            assertThat(audit.list).isEmpty();
            complete(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertThat(lines()).containsExactly("action=CATEGORY_CREATE actorMemberId=9 target=category:12 result=FAILURE");
        }

        @Test
        @DisplayName("결과를 알 수 없게 끝나도(STATUS_UNKNOWN) FAILURE")
        void unknown() {
            AdminAuditLog.write("CATEGORY_CREATE", 9L, null, true);

            complete(TransactionSynchronization.STATUS_UNKNOWN);
            assertThat(lines()).containsExactly("action=CATEGORY_CREATE actorMemberId=9 target=- result=FAILURE");
        }

        @Test
        @DisplayName("업무가 실패(succeeded=false)했으면 커밋돼도 FAILURE")
        void failedButCommitted() {
            AdminAuditLog.write("CATEGORY_DELETE", 9L, AdminAuditLog.target("category", 12L), false);

            complete(TransactionSynchronization.STATUS_COMMITTED);
            assertThat(lines()).containsExactly("action=CATEGORY_DELETE actorMemberId=9 target=category:12 result=FAILURE");
        }

        @Test
        @DisplayName("형식이 틀린 행위는 트랜잭션 안에서도 바로 IllegalArgumentException — 동기화를 걸지 않는다")
        void malformedActionFailsFast() {
            assertThatThrownBy(() -> AdminAuditLog.write("bad", 9L, null, true)).isInstanceOf(IllegalArgumentException.class);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        }

        private void complete(int status) {
            TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCompletion(status));
        }
    }

    private List<String> lines() {
        return audit.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static ListAppender<ILoggingEvent> attach(String loggerName) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerName);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
