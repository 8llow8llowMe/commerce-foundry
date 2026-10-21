package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.MemberEntity;
import com.commercefoundry.domainlayer.member.application.command.AdminSeedCommand;
import com.commercefoundry.domainlayer.member.application.info.AdminSeedOutcome;
import com.commercefoundry.domainlayer.member.application.port.in.AdminSeedUseCase;
import com.commercefoundry.domainlayer.member.application.port.out.MemberRepositoryPort;
import com.commercefoundry.domainlayer.member.domain.model.Member;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jwt.SignedJWT;
import jakarta.persistence.EntityManager;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 흐름 (#6) — 초기 관리자 시드 → 관리자 로그인(access 만) → 어드민 회원 조회(마스킹 · 최신순 · 페이지)를 실제 빈 구성(보안 체인 · Facade · Processor ·
 * 어댑터 · H2)으로 본다. 애노테이션은 {@link AuthServiceApplicationTests} 와 같아 컨텍스트를 같이 쓴다. 관리자 로그인은 refresh 저장소(Redis)를 부르지 않아
 * 스텁 없이 돈다. H2 는 컨텍스트 동안 살아 있으므로 이메일 · 아이디를 테스트마다 새로 만든다.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AdminFlowIntegrationTest {

    private static final String ADMIN_LOGIN = "/api/v1/auth/admin/login";
    private static final String ADMIN_MEMBERS = "/api/v1/admin/members";
    private static final String PASSWORD = "admin-password-1";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private AdminSeedUseCase adminSeedUseCase;

    @Autowired
    private MemberRepositoryPort memberRepositoryPort;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("시드는 멱등이다 — 처음 CREATED, 다시 부르면 ALREADY_ADMIN 이고 다른 비밀번호로 불러도 덮어쓰지 않는다")
    void seedIsIdempotent() {
        String email = uniqueEmail();

        assertThat(adminSeedUseCase.seed(new AdminSeedCommand(email.toUpperCase(), PASSWORD, null))).isEqualTo(AdminSeedOutcome.CREATED);
        assertThat(adminSeedUseCase.seed(new AdminSeedCommand(email, PASSWORD, null))).isEqualTo(AdminSeedOutcome.ALREADY_ADMIN);
        assertThat(adminSeedUseCase.seed(new AdminSeedCommand(email, "other-password-2", "새 이름"))).isEqualTo(AdminSeedOutcome.ALREADY_ADMIN);

        assertThat(memberRepositoryPort.findByEmail(email)).hasValueSatisfying(admin -> {
            assertThat(admin.role()).isEqualTo(MemberRole.ADMIN);
            assertThat(admin.name()).isEqualTo("관리자");
            assertThat(admin.phone()).isNull();
        });
        assertThat(login(email, PASSWORD)).hasStatusOk();
        assertThat(login(email, "other-password-2")).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_030");
    }

    @Test
    @DisplayName("같은 이메일의 고객이 있으면 승격하지 않는다 — CUSTOMER_EXISTS 이고 역할 · 비밀번호가 그대로다")
    void seedDoesNotPromoteCustomer() {
        String email = uniqueEmail();
        Member customer = memberRepositoryPort.save(Member.signUp(System.nanoTime(), email, "$2a$10$customer-hash", "홍길동", null));

        assertThat(adminSeedUseCase.seed(new AdminSeedCommand(email, PASSWORD, null))).isEqualTo(AdminSeedOutcome.CUSTOMER_EXISTS);

        assertThat(memberRepositoryPort.findById(customer.id())).hasValueSatisfying(member -> {
            assertThat(member.role()).isEqualTo(MemberRole.CUSTOMER);
            assertThat(member.passwordHash()).isEqualTo("$2a$10$customer-hash");
        });
    }

    @Test
    @DisplayName("관리자 로그인은 role=ADMIN access 만 주고 Cache-Control: no-store 다 — 성공 · 실패가 감사 로그에 남고 이메일 · 비밀번호는 남지 않는다")
    void adminLogin(CapturedOutput output) throws Exception {
        String email = uniqueEmail();
        adminSeedUseCase.seed(new AdminSeedCommand(email, PASSWORD, null));
        long adminId = memberRepositoryPort.findByEmail(email).orElseThrow().id();

        MvcTestResult result = login(email.toUpperCase(), PASSWORD);

        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("refreshToken");
        SignedJWT token = SignedJWT.parse(JsonPath.read(body, "$.dataBody.accessToken"));
        assertThat(token.getJWTClaimsSet().getSubject()).isEqualTo(String.valueOf(adminId));
        assertThat(token.getJWTClaimsSet().getStringClaim(MemberRole.CLAIM_NAME)).isEqualTo("ADMIN");
        assertThat(JsonPath.<Integer>read(body, "$.dataBody.accessTokenExpiresIn")).isBetween(1790, 1800);

        assertThat(login(email, "wrong-password-9")).hasStatus(HttpStatus.BAD_REQUEST);

        assertThat(output.getOut())
            .contains("action=ADMIN_LOGIN actorMemberId=" + adminId + " target=member:" + adminId + " result=SUCCESS")
            .contains("action=ADMIN_LOGIN actorMemberId=- target=member:" + adminId + " result=FAILURE")
            .doesNotContain(email, PASSWORD, "wrong-password-9");
    }

    @Test
    @DisplayName("관리자 로그인은 고객 계정 · 없는 이메일 · 틀린 비밀번호를 같은 400 · AUTH_030 으로, 고객 로그인은 관리자 계정을 AUTH_001 로 거절한다")
    void loginsRejectOtherRole() {
        String adminEmail = uniqueEmail();
        adminSeedUseCase.seed(new AdminSeedCommand(adminEmail, PASSWORD, null));
        String customerEmail = uniqueEmail();
        assertThat(signUp(customerEmail)).hasStatus(HttpStatus.CREATED);

        for (MvcTestResult rejected : List.of(login(customerEmail, "mokoyuri2026!"), login(uniqueEmail(), PASSWORD), login(adminEmail, "wrong-password-9"))) {
            assertThat(rejected).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(rejected).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_030");
            assertThat(rejected).bodyJson().extractingPath("$.dataHeader.resultMessage").isEqualTo("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        MvcTestResult customerLogin = mvc.post().uri("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(adminEmail, PASSWORD)).exchange();
        assertThat(customerLogin).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(customerLogin).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("AUTH_001");
    }

    @Test
    @DisplayName("관리자 로그인 토큰으로 회원 목록을 읽는다 — 최신순(아이디 내림차순) · 마스킹 · 자르기 전 건수, 역할 조건, 조회가 감사 로그에 남는다")
    void searchMembersWithAdminToken(CapturedOutput output) {
        String adminEmail = uniqueEmail();
        adminSeedUseCase.seed(new AdminSeedCommand(adminEmail, PASSWORD, "김관리"));
        long adminId = memberRepositoryPort.findByEmail(adminEmail).orElseThrow().id();
        String adminToken = "Bearer " + JsonPath.read(content(login(adminEmail, PASSWORD)), "$.dataBody.accessToken");

        // 다른 테스트가 넣은 행보다 앞에 오도록 가장 큰 아이디 근처를 쓴다 — 최신순 첫 페이지가 이 세 행이다
        long newest = Long.MAX_VALUE - Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000L) * 10;
        String firstEmail = "first-" + UUID.randomUUID() + "@example.com";
        String secondEmail = "second-" + UUID.randomUUID() + "@example.com";
        insert(newest - 2, firstEmail, "홍길동", "01012345678", MemberRole.CUSTOMER);
        insert(newest - 1, secondEmail, "남궁민수", null, MemberRole.CUSTOMER);
        insert(newest, "third-" + UUID.randomUUID() + "@example.com", "이몽", "0101234567", MemberRole.CUSTOMER);

        MvcTestResult page = mvc.get().uri(ADMIN_MEMBERS + "?size=3").header(HttpHeaders.AUTHORIZATION, adminToken).exchange();

        assertThat(page).hasStatusOk();
        assertThat(page).bodyJson().extractingPath("$.dataBody.contents[*].memberId")
            .isEqualTo(List.of(String.valueOf(newest), String.valueOf(newest - 1), String.valueOf(newest - 2)));
        assertThat(page).bodyJson().extractingPath("$.dataBody.contents[1].email").isEqualTo("se***@e***.com");
        assertThat(page).bodyJson().extractingPath("$.dataBody.contents[1].name").isEqualTo("남*수");
        assertThat(page).bodyJson().extractingPath("$.dataBody.contents[1].phone").isNull();
        assertThat(page).bodyJson().extractingPath("$.dataBody.contents[2].phone").isEqualTo("010-****-5678");
        assertThat(page).bodyJson().extractingPath("$.dataBody.size").isEqualTo(3);
        assertThat(JsonPath.<Integer>read(content(page), "$.dataBody.totalElements")).isGreaterThanOrEqualTo(4);
        assertThat(content(page)).doesNotContain(firstEmail, secondEmail, "홍길동", "남궁민수", "01012345678", "passwordHash", "version");

        MvcTestResult admins = mvc.get().uri(ADMIN_MEMBERS + "?role=ADMIN&size=100").header(HttpHeaders.AUTHORIZATION, adminToken).exchange();
        assertThat(admins).hasStatusOk();
        assertThat(JsonPath.<List<String>>read(content(admins), "$.dataBody.contents[*].role")).isNotEmpty().containsOnly("ADMIN");
        assertThat(JsonPath.<List<String>>read(content(admins), "$.dataBody.contents[*].memberId")).contains(String.valueOf(adminId));

        assertThat(output.getOut())
            .contains("action=MEMBER_SEARCH actorMemberId=" + adminId + " target=members?role=ALL&page=0&size=3 result=SUCCESS")
            .contains("action=MEMBER_SEARCH actorMemberId=" + adminId + " target=members?role=ADMIN&page=0&size=100 result=SUCCESS");
    }

    @Test
    @DisplayName("고객 토큰은 회원 목록에서 403 · SECURITY_005, 무토큰은 401 · SECURITY_001 이다")
    void customerTokenIsForbidden() {
        String customer = "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(1L, MemberRole.CUSTOMER).tokenValue();

        MvcTestResult forbidden = mvc.get().uri(ADMIN_MEMBERS).header(HttpHeaders.AUTHORIZATION, customer).exchange();
        assertThat(forbidden).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(forbidden).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("SECURITY_005");

        MvcTestResult anonymous = mvc.get().uri(ADMIN_MEMBERS).exchange();
        assertThat(anonymous).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("SECURITY_001");
    }

    private MvcTestResult login(String email, String password) {
        return mvc.post().uri(ADMIN_LOGIN).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)).exchange();
    }

    private MvcTestResult signUp(String email) {
        String body = """
            {"email":"%s","password":"mokoyuri2026!","name":"홍길동","phone":null,
             "agreements":{"termsOfService":true,"privacyPolicy":true,"marketing":false}}
            """.formatted(email);
        return mvc.post().uri("/api/v1/auth/sign-up").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private void insert(long id, String email, String name, String phone, MemberRole role) {
        transactionTemplate.executeWithoutResult(status -> entityManager.persist(MemberEntity.builder()
            .id(id).email(email).passwordHash("$2a$10$hash").name(name).phone(phone).role(role).build()));
    }

    private static String content(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String uniqueEmail() {
        return "admin-flow-" + UUID.randomUUID() + "@example.com";
    }
}
