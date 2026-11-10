package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.display.adapter.out.persistence.repository.DisplayItemRepository;
import com.commercefoundry.domainlayer.display.adapter.out.persistence.repository.DisplaySectionRepository;
import com.commercefoundry.domainlayer.display.application.command.DisplaySectionUpsertCommand;
import com.commercefoundry.domainlayer.display.application.exception.DisplayErrorCode;
import com.commercefoundry.domainlayer.display.application.exception.DisplayException;
import com.commercefoundry.domainlayer.display.application.port.in.DisplayAdminUseCase;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItemType;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionType;
import com.commercefoundry.security.audit.AdminAuditLog;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 메인 전시 어드민 쓰기 감사(#163 PR-C)가 실제 Facade 트랜잭션(프록시 · JPA 트랜잭션 관리자 · H2)의 <b>커밋 결과</b>로 남는지 본다 — 선례 {@link CategoryAdminAuditIntegrationTest}.
 * 줄에는 행위자 · 대상 · 동작 · 결과만 남고 섹션 내용(제목 · 문구)은 싣지 않는다. 미리보기는 저장이 없어 감사 면제라 줄이 없다.
 *
 * <p>애노테이션은 {@link DisplayFlowIntegrationTest} 와 같아 컨텍스트를 같이 쓴다. 공유 H2 라 테스트가 끝나면 전시 행을 모두 지운다(선례도 같다).
 */
@SpringBootTest(properties = {"store.media.public-base-url=https://cdn.example.com/media/", "store.features.preorder=true", "store.features.looks=true"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DisplayAdminAuditIntegrationTest {

    private static final long ACTOR = 4_611_686_018_427_387_904L;
    private static final long MISSING_SECTION = 999_999_999L;
    private static final long MISSING_PRODUCT = 999_999_998L;
    /** 요청 본문에만 넣는 표식 — 감사 줄에 나오면 본문이 새어 나간 것이다. */
    private static final String SENTINEL = "SENTINEL-DISPLAY-BODY";
    private static final DisplaySectionUpsertCommand STORY = DisplaySectionUpsertCommand.builder()
        .sectionType(DisplaySectionType.STORY).title(SENTINEL + "-title").subtitle(SENTINEL + "-subtitle").body(SENTINEL + "-body")
        .visible(true).items(List.of()).build();

    @Autowired
    private DisplayAdminUseCase displayAdminUseCase;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DisplaySectionRepository displaySectionRepository;

    @Autowired
    private DisplayItemRepository displayItemRepository;

    @Autowired
    private MockMvcTester mvc;

    private final Logger auditLogger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
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
        displayItemRepository.deleteAllInBatch();
        displaySectionRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("커밋된 생성은 만든 아이디로 SUCCESS 한 줄이고, 줄에 본문(제목 · 문구)이 없다")
    void committedCreateIsSuccess() {
        long id = create();

        assertThat(lines()).containsExactly(line("DISPLAY_SECTION_CREATE", "display-section:" + id, "SUCCESS"));
        assertNoBody();
        assertThat(displaySectionRepository.existsById(id)).isTrue();
    }

    @Test
    @DisplayName("커밋된 수정 · 순서 변경 · 삭제는 각각 SUCCESS 한 줄이다 — 순서 변경은 아이디 목록 없이 고정 대상 display-section:order 다")
    void committedWritesAreSuccess() {
        long id = create();
        audit.list.clear();

        displayAdminUseCase.updateSection(id, STORY, ACTOR);
        displayAdminUseCase.reorderSections(List.of(id), ACTOR);
        displayAdminUseCase.deleteSection(id, ACTOR);

        assertThat(lines()).containsExactly(
            line("DISPLAY_SECTION_UPDATE", "display-section:" + id, "SUCCESS"),
            line("DISPLAY_SECTION_REORDER", "display-section:order", "SUCCESS"),
            line("DISPLAY_SECTION_DELETE", "display-section:" + id, "SUCCESS")
        );
        assertNoBody();
        assertThat(displaySectionRepository.existsById(id)).isFalse();
    }

    @Test
    @DisplayName("업무 실패는 FAILURE 한 줄이다 — 없는 섹션 수정 · 삭제(404 · PRODUCT_046), 순서 집합 불일치(400 · PRODUCT_057), 없는 상품으로 생성(400 · PRODUCT_050, target=-)")
    void businessFailuresAreFailure() {
        assertErrorCode(() -> displayAdminUseCase.updateSection(MISSING_SECTION, STORY, ACTOR), DisplayErrorCode.NOT_FOUND_DISPLAY_SECTION);
        assertErrorCode(() -> displayAdminUseCase.deleteSection(MISSING_SECTION, ACTOR), DisplayErrorCode.NOT_FOUND_DISPLAY_SECTION);
        assertErrorCode(() -> displayAdminUseCase.reorderSections(List.of(MISSING_SECTION), ACTOR), DisplayErrorCode.DISPLAY_SECTION_ORDER_MISMATCH);
        DisplaySectionUpsertCommand unknownProduct = DisplaySectionUpsertCommand.builder()
            .sectionType(DisplaySectionType.PRODUCT_PICK).title(SENTINEL).visible(true)
            .items(List.of(DisplaySectionUpsertCommand.Item.builder().itemType(DisplayItemType.PRODUCT).productId(MISSING_PRODUCT).caption(SENTINEL).build()))
            .build();
        assertErrorCode(() -> displayAdminUseCase.createSection(unknownProduct, ACTOR), DisplayErrorCode.DISPLAY_PRODUCT_NOT_FOUND);

        assertThat(lines()).containsExactly(
            line("DISPLAY_SECTION_UPDATE", "display-section:" + MISSING_SECTION, "FAILURE"),
            line("DISPLAY_SECTION_DELETE", "display-section:" + MISSING_SECTION, "FAILURE"),
            line("DISPLAY_SECTION_REORDER", "display-section:order", "FAILURE"),
            line("DISPLAY_SECTION_CREATE", "-", "FAILURE")
        );
        assertThat(lines()).noneMatch(line -> line.contains(String.valueOf(MISSING_PRODUCT)));
        assertNoBody();
        assertThat(displaySectionRepository.count()).isZero();
    }

    @Test
    @DisplayName("업무는 성공했지만 바깥 트랜잭션이 롤백되면 생성 · 수정 · 순서 변경 줄은 롤백 뒤에 FAILURE 로 남고 섹션도 없다 — 롤백 전에는 아무 줄도 쓰지 않는다")
    void rolledBackWritesAreFailure() {
        AtomicReference<Long> id = new AtomicReference<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            id.set(Long.parseLong(displayAdminUseCase.createSection(STORY, ACTOR).sectionId()));
            displayAdminUseCase.updateSection(id.get(), STORY, ACTOR);
            displayAdminUseCase.reorderSections(List.of(id.get()), ACTOR);
            assertThat(audit.list).as("트랜잭션이 끝나기 전에는 줄을 쓰지 않는다").isEmpty();
            status.setRollbackOnly();
        });

        assertThat(lines()).containsExactly(
            line("DISPLAY_SECTION_CREATE", "display-section:" + id.get(), "FAILURE"),
            line("DISPLAY_SECTION_UPDATE", "display-section:" + id.get(), "FAILURE"),
            line("DISPLAY_SECTION_REORDER", "display-section:order", "FAILURE")
        );
        assertThat(displaySectionRepository.existsById(id.get())).isFalse();
    }

    @Test
    @DisplayName("롤백된 삭제도 FAILURE 이고 섹션은 그대로 남는다")
    void rolledBackDeleteIsFailure() {
        long id = create();
        audit.list.clear();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            displayAdminUseCase.deleteSection(id, ACTOR);
            status.setRollbackOnly();
        });

        assertThat(lines()).containsExactly(line("DISPLAY_SECTION_DELETE", "display-section:" + id, "FAILURE"));
        assertThat(displaySectionRepository.existsById(id)).isTrue();
    }

    @Test
    @DisplayName("HTTP 경로는 access 토큰의 memberId 를 행위자로 넘긴다 — 없는 섹션 삭제는 404 와 FAILURE 한 줄이다")
    void controllerPassesTokenMemberId() {
        assertThat(exchange("DELETE", "/api/v1/admin/displays/sections/" + MISSING_SECTION, null)).hasStatus(HttpStatus.NOT_FOUND);

        assertThat(lines()).containsExactly(
            "action=DISPLAY_SECTION_DELETE actorMemberId=" + TestTokens.MEMBER_ID + " target=display-section:" + MISSING_SECTION + " result=FAILURE"
        );
    }

    @Test
    @DisplayName("미리보기는 감사 면제다 — 성공해도 줄이 없고 섹션도 저장하지 않는다")
    void previewWritesNoAuditLine() {
        String body = "{\"sections\":[{\"sectionType\":\"STORY\",\"title\":\"" + SENTINEL + "\",\"visible\":true,\"items\":[]}]}";

        assertThat(exchange("POST", "/api/v1/admin/displays/preview", body)).hasStatusOk();

        assertThat(audit.list).isEmpty();
        assertThat(displaySectionRepository.count()).isZero();
    }

    private long create() {
        return Long.parseLong(displayAdminUseCase.createSection(STORY, ACTOR).sectionId());
    }

    private MvcTestResult exchange(String method, String uri, String body) {
        var request = mvc.method(HttpMethod.valueOf(method)).uri(uri)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.ADMIN).tokenValue());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request.exchange();
    }

    private static void assertErrorCode(ThrowingCallable call, DisplayErrorCode errorCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(DisplayException.class, e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
    }

    private void assertNoBody() {
        assertThat(lines()).noneMatch(line -> line.contains(SENTINEL));
    }

    private List<String> lines() {
        return audit.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static String line(String action, String target, String result) {
        return "action=" + action + " actorMemberId=" + ACTOR + " target=" + target + " result=" + result;
    }
}
