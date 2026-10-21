package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.MemberAgreementEntity;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.MemberEntity;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.AgreementType;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 회원가입 → 내 정보 조회 → 수정 흐름을 실제 빈 구성(보안 체인 · Facade · Processor · 어댑터 · H2)으로 본다.
 * 애노테이션은 {@link AuthServiceApplicationTests} 와 같아 컨텍스트를 같이 쓴다. H2 는 컨텍스트 동안 살아 있으므로 테스트마다 이메일을 새로 만든다.
 *
 * <p>엔진에 기대는 동작(PostgreSQL 제약 이름 · 락)은 리포지토리 테스트({@code MemberPersistenceAdapterTest}, Testcontainers)가 본다.
 * 여기서는 같은 변환이 H2 에서도 도메인 오류로 나오는지만 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MemberFlowIntegrationTest {

    private static final String PASSWORD = "mokoyuri2026!";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MemberRepositoryPort memberRepositoryPort;

    @Test
    @DisplayName("가입하면 BCrypt 해시 · 정규화한 값 · CUSTOMER(본문의 role 무시)로 저장되고 동의 3줄이 서버 약관 버전으로 남는다 → 내 정보 → 수정 → 다시 조회")
    void signUpThenProfile() throws Exception {
        String email = uniqueEmail();

        MvcTestResult signUp = signUp(email.toUpperCase(), "010-1234-5678", false);
        assertThat(signUp).hasStatus(HttpStatus.CREATED);
        String signUpBody = signUp.getResponse().getContentAsString(StandardCharsets.UTF_8);
        long memberId = Long.parseLong(JsonPath.read(signUpBody, "$.dataBody.memberId"));

        MemberEntity saved = transactionTemplate.execute(status -> entityManager.find(MemberEntity.class, memberId));
        assertThat(saved.getEmail()).isEqualTo(email);
        assertThat(saved.getPhone()).isEqualTo("01012345678");
        assertThat(saved.getRole()).as("본문의 \"role\":\"ADMIN\" 은 무시된다").isEqualTo(MemberRole.CUSTOMER);
        assertThat(saved.getPasswordHash()).startsWith("$2").isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, saved.getPasswordHash())).isTrue();
        assertThat(signUpBody).doesNotContain(PASSWORD, saved.getPasswordHash(), "password");
        assertThat(agreements(memberId))
            .extracting(MemberAgreementEntity::getAgreementType, MemberAgreementEntity::isAgreed, MemberAgreementEntity::getTermsVersion)
            .containsExactlyInAnyOrder(
                tuple(AgreementType.TERMS_OF_SERVICE, true, "2026-10-01"),
                tuple(AgreementType.PRIVACY_POLICY, true, "2026-10-01"),
                tuple(AgreementType.MARKETING, false, "2026-10-01")
            );

        String token = bearer(memberId);
        MvcTestResult me = mvc.get().uri("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, token).exchange();
        assertThat(me).hasStatusOk();
        assertThat(me).bodyJson().extractingPath("$.dataBody.memberId").asString().isEqualTo(String.valueOf(memberId));
        assertThat(me).bodyJson().extractingPath("$.dataBody.email").isEqualTo(email);
        assertThat(me).bodyJson().extractingPath("$.dataBody.phone").isEqualTo("01012345678");
        assertThat(me).bodyJson().extractingPath("$.dataBody.marketingAgreed").asBoolean().isFalse();
        assertThat(me.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(PASSWORD, saved.getPasswordHash(), "password", "role");

        MvcTestResult patch = mvc.patch().uri("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" 김철수 \",\"phone\":\"\",\"marketingAgreed\":true}").exchange();
        assertThat(patch).hasStatusOk();
        assertThat(patch).bodyJson().extractingPath("$.dataBody.name").isEqualTo("김철수");
        assertThat(patch).bodyJson().extractingPath("$.dataBody.phone").isNull();
        assertThat(patch).bodyJson().extractingPath("$.dataBody.marketingAgreed").asBoolean().isTrue();
        assertThat(agreements(memberId)).filteredOn(agreement -> agreement.getAgreementType() == AgreementType.MARKETING).hasSize(2);

        MvcTestResult again = mvc.get().uri("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, token).exchange();
        assertThat(again).bodyJson().extractingPath("$.dataBody.name").isEqualTo("김철수");
        assertThat(again).bodyJson().extractingPath("$.dataBody.marketingAgreed").asBoolean().isTrue();

        // 같은 값을 다시 보내면 이력을 늘리지 않는다
        mvc.patch().uri("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"marketingAgreed\":true}").exchange();
        assertThat(agreements(memberId)).filteredOn(agreement -> agreement.getAgreementType() == AgreementType.MARKETING).hasSize(2);
    }

    @Test
    @DisplayName("대소문자만 다른 이메일로 다시 가입하면 409 · AUTH_002 다")
    void duplicateSignUp() {
        String email = uniqueEmail();
        assertThat(signUp(email, null, true)).hasStatus(HttpStatus.CREATED);

        MvcTestResult duplicate = signUp(email.toUpperCase(), null, true);

        assertThat(duplicate).hasStatus(HttpStatus.CONFLICT);
        assertThat(duplicate).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_002");
    }

    @Test
    @DisplayName("사전 조회를 지나 유니크 키에 걸려도 AUTH_002 다 (H2 제약 이름)")
    void uniqueKeyViolationBecomesDuplicate() {
        String email = uniqueEmail();
        memberRepositoryPort.save(Member.signUp(System.nanoTime(), email, "$2a$10$hash", "홍길동", null));

        assertThatThrownBy(() -> memberRepositoryPort.save(Member.signUp(System.nanoTime(), email, "$2a$10$hash", "김철수", null)))
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.DUPLICATE_EMAIL);
    }

    @Test
    @DisplayName("옛 버전으로 저장하면 AUTH_004 다 (낙관적 락)")
    void staleVersionBecomesConflict() {
        long memberId = System.nanoTime();
        Member stale = memberRepositoryPort.save(Member.signUp(memberId, uniqueEmail(), "$2a$10$hash", "홍길동", null));
        memberRepositoryPort.save(stale.changeProfile("먼저 고침", null));

        assertThatThrownBy(() -> memberRepositoryPort.save(stale.changeProfile("나중에 고침", null)))
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.MEMBER_UPDATE_CONFLICT);
    }

    @Test
    @DisplayName("같은 버전을 읽은 두 수정(마케팅만 변경) 중 뒤의 것은 AUTH_004 다 — 마케팅 변경도 회원 버전을 올린다")
    void concurrentMarketingChangeConflicts() {
        Member read = memberRepositoryPort.save(Member.signUp(System.nanoTime(), uniqueEmail(), "$2a$10$hash", "홍길동", null));

        transactionTemplate.executeWithoutResult(status -> memberRepositoryPort.incrementVersion(read));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> memberRepositoryPort.incrementVersion(read)))
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.MEMBER_UPDATE_CONFLICT);
        assertThat(memberRepositoryPort.findById(read.id())).hasValueSatisfying(member -> assertThat(member.version()).isEqualTo(1L));
    }

    @Test
    @DisplayName("규칙 밖 전화번호로 저장된 행(이관 데이터)도 내 정보 조회 · 이름 수정이 된다 — 복원은 검증하지 않는다")
    void outOfRuleRowIsReadable() {
        long memberId = System.nanoTime();
        transactionTemplate.executeWithoutResult(status -> entityManager.persist(MemberEntity.builder()
            .id(memberId).email(uniqueEmail()).passwordHash("$2a$10$hash").name("홍길동").phone("02-123-4567").role(MemberRole.CUSTOMER).build()));

        MvcTestResult me = mvc.get().uri("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, bearer(memberId)).exchange();
        assertThat(me).hasStatusOk();
        assertThat(me).bodyJson().extractingPath("$.dataBody.phone").isEqualTo("02-123-4567");

        MvcTestResult patch = mvc.patch().uri("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, bearer(memberId))
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"김철수\"}").exchange();
        assertThat(patch).hasStatusOk();
        assertThat(patch).bodyJson().extractingPath("$.dataBody.phone").isEqualTo("02-123-4567");
    }

    @Test
    @DisplayName("대문자가 섞인 이메일은 애플리케이션을 거치지 않아도 DB 가 거절한다 (ck_member_email_normalized)")
    void emailMustBeLowercaseInDatabase() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            entityManager.persist(MemberEntity.builder()
                .id(System.nanoTime()).email("Upper-" + UUID.randomUUID() + "@example.com").passwordHash("$2a$10$hash").name("홍길동").role(MemberRole.CUSTOMER)
                .build());
            entityManager.flush();
        })).rootCause().message().containsIgnoringCase("ck_member_email_normalized");
    }

    /**
     * #155 회귀 — 전각 공백(U+3000)만 넣은 값이 요청 검증을 지나 도메인 {@code strip()} 에서 {@link IllegalArgumentException}(500)이 되지 않는다.
     * 요청 검증과 도메인이 같은 공백 기준({@link Character#isWhitespace})이어야 한다.
     */
    @Test
    @DisplayName("전각 공백만인 이름 · 이메일은 500 이 아니라 400 이다 — 가입 이름 AUTH_107, 내 정보 수정 이름 AUTH_114, 로그인 이메일 AUTH_101")
    void ideographicSpaceIsBadRequest() throws Exception {
        String ideographicSpace = "\\u3000";
        String signUpBody = """
            {"email":"%s","password":"%s","name":"%s","phone":null,
             "agreements":{"termsOfService":true,"privacyPolicy":true,"marketing":false}}
            """.formatted(uniqueEmail(), PASSWORD, ideographicSpace);
        MvcTestResult signUp = mvc.post().uri("/api/v1/auth/sign-up").contentType(MediaType.APPLICATION_JSON).content(signUpBody).exchange();
        assertThat(signUp).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(signUp).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_107");

        String created = signUp(uniqueEmail(), null, false).getResponse().getContentAsString(StandardCharsets.UTF_8);
        long memberId = Long.parseLong(JsonPath.read(created, "$.dataBody.memberId"));
        MvcTestResult patch = mvc.patch().uri("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, bearer(memberId))
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"%s\"}".formatted(ideographicSpace)).exchange();
        assertThat(patch).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(patch).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_114");
        assertThat(memberRepositoryPort.findById(memberId)).hasValueSatisfying(member -> assertThat(member.name()).isEqualTo("홍길동"));

        MvcTestResult login = mvc.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(ideographicSpace, PASSWORD)).exchange();
        assertThat(login).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(login).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_101");
    }

    @Test
    @DisplayName("토큰은 유효하지만 회원이 없으면 404 · AUTH_003 이다")
    void unknownMember() {
        MvcTestResult result = mvc.get().uri("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, bearer(1L)).exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_003");
    }

    private MvcTestResult signUp(String email, String phone, boolean marketing) {
        String phoneJson = phone == null ? "null" : "\"" + phone + "\"";
        // role 은 요청 DTO 에 없는 칸이다 — 보내도 무시되고 언제나 CUSTOMER 로 가입한다
        String body = """
            {"email":"%s","password":"%s","name":" 홍길동 ","phone":%s,"role":"ADMIN",
             "agreements":{"termsOfService":true,"privacyPolicy":true,"marketing":%s}}
            """.formatted(email, PASSWORD, phoneJson, marketing);
        return mvc.post().uri("/api/v1/auth/sign-up").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private List<MemberAgreementEntity> agreements(long memberId) {
        return transactionTemplate.execute(status -> entityManager
            .createQuery("select a from MemberAgreementEntity a where a.memberId = :memberId", MemberAgreementEntity.class)
            .setParameter("memberId", memberId)
            .getResultList());
    }

    private static String uniqueEmail() {
        return "member-" + UUID.randomUUID() + "@example.com";
    }

    private static String bearer(long memberId) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(memberId, MemberRole.CUSTOMER).tokenValue();
    }
}
