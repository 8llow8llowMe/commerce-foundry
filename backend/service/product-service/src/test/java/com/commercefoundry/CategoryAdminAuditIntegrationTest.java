package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.product.application.command.CategoryUpsertCommand;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.in.CategoryAdminUseCase;
import com.commercefoundry.security.audit.AdminAuditLog;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 어드민 쓰기 감사(#163)가 실제 Facade 트랜잭션(프록시 · JPA 트랜잭션 관리자 · H2)의 <b>커밋 결과</b>로 남는지 본다 — {@code AdminAuditLog} 는 트랜잭션 안의 호출을
 * {@code afterCompletion} 까지 미루고, 커밋됐고 업무가 성공했을 때만 {@code SUCCESS} 를 쓴다 ({@code security-guide.md} §11-4).
 *
 * <p>테스트 메서드는 트랜잭션 밖이다({@code @SpringBootTest} 기본). 애노테이션은 {@link ProductServiceApplicationTests} 와 같아 컨텍스트를 같이 쓴다.
 * 공유 H2 라 만든 카테고리는 아이디로 지운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CategoryAdminAuditIntegrationTest {

    private static final long ACTOR = 4_611_686_018_427_387_904L;
    private static final long MISSING_PARENT = 999_999_999L;

    @Autowired
    private CategoryAdminUseCase categoryAdminUseCase;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Logger auditLogger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
    private final List<Long> created = new ArrayList<>();
    private ListAppender<ILoggingEvent> audit;

    @BeforeEach
    void attach() {
        audit = new ListAppender<>();
        audit.start();
        auditLogger.addAppender(audit);
    }

    @AfterEach
    void cleanUp() {
        auditLogger.detachAppender(audit);
        created.forEach(id -> jdbcTemplate.update("delete from product.category where id = ?", id));
    }

    @Test
    @DisplayName("커밋된 생성은 만든 아이디로 SUCCESS 한 줄이다")
    void committedCreateIsSuccess() {
        long id = create("상의-감사");

        assertThat(lines()).containsExactly(line(id, "SUCCESS"));
        assertThat(countById(id)).isOne();
    }

    @Test
    @DisplayName("업무 실패(없는 상위 400 · PRODUCT_044)는 Facade 트랜잭션이 롤백되고 target=- FAILURE 한 줄이다 — SUCCESS 는 없다")
    void businessFailureIsFailure() {
        assertThatThrownBy(() -> categoryAdminUseCase.createCategory(new CategoryUpsertCommand(MISSING_PARENT, "하의-감사", 0, true), ACTOR))
            .isInstanceOfSatisfying(ProductException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.CATEGORY_PARENT_NOT_FOUND));

        assertThat(lines()).containsExactly("action=CATEGORY_CREATE actorMemberId=" + ACTOR + " target=- result=FAILURE");
    }

    @Test
    @DisplayName("업무는 성공했지만 바깥 트랜잭션이 롤백되면 줄은 롤백 뒤에 FAILURE 로 남고 카테고리도 없다 — 롤백 전에는 아무 줄도 쓰지 않는다")
    void rolledBackCreateIsFailure() {
        AtomicReference<Long> id = new AtomicReference<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            id.set(Long.parseLong(categoryAdminUseCase.createCategory(new CategoryUpsertCommand(null, "롤백-감사", 0, true), ACTOR).categoryId()));
            assertThat(audit.list).as("트랜잭션이 끝나기 전에는 줄을 쓰지 않는다").isEmpty();
            status.setRollbackOnly();
        });

        assertThat(lines()).containsExactly(line(id.get(), "FAILURE"));
        assertThat(countById(id.get())).isZero();
    }

    @Test
    @DisplayName("커밋 시점 실패 — 같은 트랜잭션의 앞선 업무 실패로 rollback-only 가 된 뒤의 성공한 생성은 커밋이 UnexpectedRollbackException 으로 끝나 FAILURE 다")
    void commitFailureIsFailure() {
        AtomicReference<Long> id = new AtomicReference<>();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                categoryAdminUseCase.createCategory(new CategoryUpsertCommand(MISSING_PARENT, "실패-감사", 0, true), ACTOR);
            } catch (ProductException expected) {
                // 참여한 Facade 가 예외로 끝나 바깥 트랜잭션은 rollback-only 다 — 삼켜도 커밋되지 않는다
            }
            id.set(Long.parseLong(categoryAdminUseCase.createCategory(new CategoryUpsertCommand(null, "커밋실패-감사", 0, true), ACTOR).categoryId()));
        })).isInstanceOf(UnexpectedRollbackException.class);

        assertThat(lines()).containsExactlyInAnyOrder(
            "action=CATEGORY_CREATE actorMemberId=" + ACTOR + " target=- result=FAILURE",
            line(id.get(), "FAILURE")
        );
        assertThat(lines()).noneMatch(line -> line.endsWith("result=SUCCESS"));
        assertThat(countById(id.get())).isZero();
    }

    private long create(String name) {
        long id = Long.parseLong(categoryAdminUseCase.createCategory(new CategoryUpsertCommand(null, name, 0, true), ACTOR).categoryId());
        created.add(id);
        return id;
    }

    private long countById(long id) {
        return jdbcTemplate.queryForObject("select count(*) from product.category where id = ?", Long.class, id);
    }

    private List<String> lines() {
        return audit.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static String line(long categoryId, String result) {
        return "action=CATEGORY_CREATE actorMemberId=" + ACTOR + " target=category:" + categoryId + " result=" + result;
    }
}
