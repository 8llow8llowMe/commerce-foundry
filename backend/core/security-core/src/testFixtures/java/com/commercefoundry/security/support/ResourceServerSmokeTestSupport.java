package com.commercefoundry.security.support;

import static com.commercefoundry.security.support.SecurityEnvelopeAssertions.assertEnvelope;
import static com.commercefoundry.security.support.SecurityEnvelopeAssertions.assertWwwAuthenticate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.commercefoundry.security.audit.AdminAuditExempt;
import com.commercefoundry.security.audit.AdminAudited;
import com.commercefoundry.security.error.SecurityErrorCode;
import com.commercefoundry.security.internal.InternalTokenProperties;
import com.commercefoundry.security.issuer.JwtIssuer;
import com.commercefoundry.security.principal.MemberRole;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 서블릿 서비스의 인가 스모크. 서비스가 {@code spring-boot-starter-security} 와 {@code ResourceServerSecurityConfigurer} import 를
 * 빠뜨려 인증 없이 뜨는 구성 실패를 잡는다 ({@code security-guide.md} §5-3). 서비스 간 인증({@code /internal/**}, §9)이
 * 걸렸는지도 본다 — {@code InternalResourceServerConfigurer} 를 빠뜨리면 internal 경로가 007 이 아니라 001 로 막힌다. 서비스는 한 줄로 상속한다.
 *
 * <pre>{@code
 * class ProductServiceSecuritySmokeTest extends ResourceServerSmokeTestSupport {
 * }
 * }</pre>
 *
 * <ul>
 *   <li>애노테이션은 서비스의 {@code *ApplicationTests} 와 <b>정확히 같다</b> — 컨텍스트 캐시를 같이 써 서비스 컨텍스트를 한 번만 띄운다.
 *       서비스 쪽 애노테이션을 바꾸면 여기도 바꾼다.</li>
 *   <li>서비스 컨트롤러에 기대지 않는다 — 어느 서비스에도 없는 경로({@value #ADMIN_PATH} · {@value #PUBLIC_PATH})로 URL 규칙과 토큰 필터만 본다.
 *       규칙을 통과하면 매핑이 없어 404 다. 대소문자 우회는 컨트롤러가 있어야 의미가 있어 security-core 통합 테스트에 두고,
 *       여기서는 서비스 MVC 의 경로 매칭이 URL 규칙과 같은 대소문자 규칙인지 본다.</li>
 *   <li>{@code @PreAuthorize("isAuthenticated()")} 의 익명 401 은 #4 또는 서비스에 첫 {@code isAuthenticated()} 핸들러가 생기는 이슈에서 서비스의 {@code *SecuritySmokeTest} 에
 *       메서드로 더한다 — {@link #perform} · {@link #bearer} · {@link SecurityEnvelopeAssertions} 를 쓰고 같은 컨텍스트를 공유한다.</li>
 *   <li>004 · 006 은 디코더 장애를 흉내 내야 해 security-core 통합 테스트에만 둔다.</li>
 *   <li>서비스 간 인증은 테스트 전용 internal 핸들러({@link InternalSecuritySmokeController#PATH}, 허용 호출자 고정)로 본다 — 토큰 없음 · 사용자 access 토큰 ·
 *       {@code aud} 불일치 401 · 007, 허용 목록 밖 호출자 403 · 005, 허용된 호출자 200. 핸들러는 {@link InternalSecuritySmokeContextCustomizerFactory} 가
 *       모든 서블릿 테스트 컨텍스트에 등록하므로 {@code *ApplicationTests} 와 컨텍스트 공유가 깨지지 않는다.</li>
 *   <li>인가 선언 누락 가드 — 서비스 컨텍스트의 모든 MVC 핸들러를 돌며 {@code /internal/} 핸들러는 허용 호출자({@code hasAuthority('SERVICE_…')}),
 *       {@code /api/} 핸들러는 {@code @PreAuthorize} 가 있는지 본다. 실제 매핑을 보는 런타임 가드이고, 패키지 기준 정적 규칙(어드민의 운영자 권한 식 ·
 *       전역 Feign 설정 · {@code RequestContextHolder})은 {@link ControllerAuthorizationArchTestSupport} 가 맡는다. 허용 호출자 판정은 둘이
 *       {@link AuthorizationExpressions} 를 같이 쓴다.</li>
 *   <li>관리자 감사 선언 누락 가드(#163) — {@code /api/v1/admin} 의 쓰기 핸들러는 {@link AdminAudited} 또는 {@link AdminAuditExempt} 를 선언한다.
 *       아직 붙이지 않은 핸들러는 서비스가 {@link #auditDeclarationPending()} 에 이슈 번호와 함께 둔다.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
public abstract class ResourceServerSmokeTestSupport {

    /** {@code /api/v1/admin/**} 규칙에 걸리는, 어느 서비스에도 매핑되지 않는 경로. */
    protected static final String ADMIN_PATH = "/api/v1/admin/smoke";
    /** 어드민이 아닌, 어느 서비스에도 매핑되지 않는 경로. */
    protected static final String PUBLIC_PATH = "/api/v1/smoke";

    private static final String INTERNAL_PREFIX = "/internal/";
    private static final String API_PREFIX = "/api/";
    /** 어드민 API 경로 머리 ({@code api-design-guide.md}). URL 규칙의 {@code /api/v1/admin/**} 와 같다. */
    private static final String ADMIN_API_PREFIX = "/api/v1/admin";
    /** 감사 줄을 남겨야 하는 어드민 쓰기 메서드 ({@code security-guide.md} §11-4). */
    private static final Set<RequestMethod> WRITE_METHODS = EnumSet.of(RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE);
    /** {@code AdminAuditLog} 가 받는 행위 이름 형식과 같다. */
    private static final String AUDIT_ACTION_REGEX = "^[A-Z][A-Z0-9_]{0,63}$";
    /** 대기 항목의 이슈 표기에 있어야 하는 이슈 번호. */
    private static final Pattern ISSUE_REFERENCE = Pattern.compile("#\\d+");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ConfigurableEnvironment environment;

    /**
     * 정규화되면 어드민 경로가 되는 원문 경로 — {@code ..} · {@code %2e} · {@code ;} · {@code //} · {@code %2f}.
     * security-core 의 {@code ResourceServerSecurityIntegrationTest} 도 이 목록을 쓴다.
     */
    public static Stream<String> nonNormalizedAdminPaths() {
        return Stream.of(
            "/api/v1/orders/../admin/x",
            "/api/v1/orders/%2e%2e/admin/x",
            "/api/v1/orders/%2E%2E/admin/x",
            "/api/v1/admin;x=y/x",
            "/api/v1/orders/..;/admin/x",
            "/api/v1//admin/x",
            "/api/v1/admin%2fx"
        );
    }

    /** 발급기({@code JwtIssuerConfigurer})를 import 하는 서비스만 {@code true} 로 바꾼다 — auth-service(#4). */
    protected boolean issuerExpected() {
        return false;
    }

    // ── URL 규칙 · 토큰 필터 ──────────────────────────────────────

    @Test
    @DisplayName("토큰 없이 어드민 경로에 오면 401 · SECURITY_001 봉투 · WWW-Authenticate: Bearer")
    void anonymousOnAdminPath() throws Exception {
        MockHttpServletResponse response = perform(get(ADMIN_PATH));

        assertEnvelope(response, SecurityErrorCode.UNAUTHORIZED);
        assertWwwAuthenticate(response, "Bearer");
    }

    @Test
    @DisplayName("CUSTOMER 토큰으로 어드민 경로에 오면 403 · SECURITY_005 봉투 · WWW-Authenticate 없음")
    void customerOnAdminPath() throws Exception {
        MockHttpServletResponse response = perform(get(ADMIN_PATH).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)));

        assertEnvelope(response, SecurityErrorCode.FORBIDDEN);
        assertWwwAuthenticate(response, null);
    }

    /**
     * 보안이 없어도 404 라 단독으로는 아무것도 증명하지 않는다. 위 401 · 403 케이스와 함께라야 "운영자 역할만 통과" 가 된다.
     * 기능 권한은 URL 층이 아니라 핸들러의 {@code @PreAuthorize} 가 가른다 ({@code security-guide.md} §3-1).
     */
    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"ADMIN", "OWNER", "MANAGER", "STAFF"})
    @DisplayName("운영자 역할(ADMIN · OWNER · MANAGER · STAFF) 토큰은 어드민 URL 규칙을 통과한다 — 매핑이 없어 404 (#236)")
    void operatorPassesAdminRule(MemberRole role) throws Exception {
        assertThat(perform(get(ADMIN_PATH).header(HttpHeaders.AUTHORIZATION, bearer(role))).getStatus()).isEqualTo(404);
    }

    /** 문자열 템플릿은 {@code %} 를 다시 인코딩하므로 {@link URI} 로 보낸다. */
    @Test
    @DisplayName("비예약 문자를 인코딩한 어드민 경로(/api/v1/%61dmin/smoke)도 규칙에 걸린다 — 무토큰 401 · CUSTOMER 403")
    void encodedAdminPathMatchesAdminRule() throws Exception {
        URI encoded = URI.create("/api/v1/%61dmin/smoke");

        assertEnvelope(perform(get(encoded)), SecurityErrorCode.UNAUTHORIZED);
        assertEnvelope(perform(get(encoded).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER))), SecurityErrorCode.FORBIDDEN);
    }

    /**
     * 방화벽은 {@code sendError(400)} 만 한다. MockMvc 는 컨테이너의 {@code /error} 재전송을 하지 않아 여기서는 본문이 비어 있다 —
     * 실제 서블릿 컨테이너에서는 {@code /error} 를 거쳐 {@code COMMON_004} 봉투가 나가고, 그 모양은 common-core 의
     * {@code ErrorPageEnvelopeSmokeTestSupport}(RANDOM_PORT) 가 본다 ({@code security-guide.md} §5-1). 여기는 상태와 우회 차단만 본다.
     */
    @ParameterizedTest
    @MethodSource("nonNormalizedAdminPaths")
    @DisplayName("정규화되면 어드민 경로가 되는 원문 경로는 무토큰 · CUSTOMER 모두 방화벽이 400 으로 끊는다 — MockMvc 라 본문 없음(실제 컨테이너는 COMMON_004 봉투)")
    void nonNormalizedPathIsRejectedByFirewall(String path) throws Exception {
        MockHttpServletResponse anonymous = perform(get(URI.create(path)));
        MockHttpServletResponse customer = perform(get(URI.create(path)).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)));

        assertThat(anonymous.getStatus()).as(path).isEqualTo(400);
        assertThat(anonymous.getContentAsByteArray()).as(path).isEmpty();
        assertThat(customer.getStatus()).as(path).isEqualTo(400);
        assertThat(customer.getContentAsByteArray()).as(path).isEmpty();
    }

    /**
     * URL 규칙({@code requestMatchers(String)})은 Spring Security 7 이 {@link PathPatternParser#defaultInstance}(대소문자 구분)로 만든다.
     * 서비스 MVC 가 대소문자를 무시하게 바뀌면 {@code /API/v1/admin/x} 가 규칙을 비껴 어드민 컨트롤러에 닿는다 — 두 매칭 규칙이 같은지 본다.
     * 우회 자체는 컨트롤러가 있는 security-core 통합 테스트가 본다.
     */
    @Test
    @DisplayName("서비스 MVC 의 경로 매칭이 URL 규칙과 같다 — PathPatternParser · 대소문자 구분")
    void mvcPathMatchingIsCaseSensitive() {
        RequestMappingHandlerMapping handlerMapping = applicationContext.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);

        assertThat(handlerMapping.getPatternParser()).as("MVC 가 PathPatternParser 로 매칭한다").isNotNull();
        assertThat(PathPatternParser.defaultInstance.isCaseSensitive()).as("URL 규칙의 경로 매칭이 대소문자를 가린다").isTrue();
        assertThat(handlerMapping.getPatternParser().isCaseSensitive()).as("MVC 경로 매칭이 URL 규칙과 같이 대소문자를 가린다").isTrue();
    }

    @Test
    @DisplayName("어드민이 아닌 경로에도 틀린 토큰을 보내면 401 · SECURITY_003 이다 — 익명으로 넘어가지 않는다")
    void invalidTokenOnPublicPath() throws Exception {
        MockHttpServletResponse response = perform(get(PUBLIC_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"));

        assertEnvelope(response, SecurityErrorCode.TOKEN_INVALID);
        assertWwwAuthenticate(response, "Bearer error=\"invalid_token\"");
    }

    @Test
    @DisplayName("만료된 토큰은 401 · SECURITY_002 다")
    void expiredToken() throws Exception {
        String expired = TestTokens.issuer(Instant.now().minus(Duration.ofHours(2))).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue();

        MockHttpServletResponse response = perform(get(PUBLIC_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired));

        assertEnvelope(response, SecurityErrorCode.TOKEN_EXPIRED);
        assertWwwAuthenticate(response, "Bearer error=\"invalid_token\", error_description=\"The access token expired\"");
    }

    /** 보안이 없어도 세션이 없을 수 있어 단독으로는 약하다. 위 401 · 403 케이스와 함께라야 "인증하고도 세션을 만들지 않는다" 가 된다. */
    @Test
    @DisplayName("인증된 요청도 세션을 만들지 않는다 (STATELESS) — Set-Cookie 없음")
    void createsNoSession() throws Exception {
        MvcResult result = mockMvc.perform(get(ADMIN_PATH).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.ADMIN))).andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    // ── 서비스 간 인증 (/internal/**) ─────────────────────────────

    @Test
    @DisplayName("서비스 토큰 없이 internal 경로에 오면 401 · SECURITY_007 · WWW-Authenticate: Bearer — internal 체인이 걸려 있다")
    void internalPathWithoutToken() throws Exception {
        MockHttpServletResponse response = perform(get(InternalSecuritySmokeController.PATH));

        assertEnvelope(response, SecurityErrorCode.SERVICE_UNAUTHORIZED);
        assertWwwAuthenticate(response, "Bearer");
    }

    @ParameterizedTest
    @EnumSource(MemberRole.class)
    @DisplayName("사용자 access 토큰으로 internal 경로에 오면 401 · SECURITY_007 — ADMIN 이어도 internal 을 부를 수 없다")
    void internalPathWithUserAccessToken(MemberRole role) throws Exception {
        assertEnvelope(perform(get(InternalSecuritySmokeController.PATH).header(HttpHeaders.AUTHORIZATION, bearer(role))), SecurityErrorCode.SERVICE_UNAUTHORIZED);
    }

    @Test
    @DisplayName("aud 가 다른 서비스인 서비스 토큰은 401 · SECURITY_007 — 다른 서비스로 가는 토큰을 이 서비스에 쓸 수 없다")
    void internalPathWithOtherAudience() throws Exception {
        String otherAudience = "not-" + serviceId();

        MockHttpServletResponse response = perform(get(InternalSecuritySmokeController.PATH)
            .header(HttpHeaders.AUTHORIZATION, TestServiceTokens.bearer(InternalSecuritySmokeController.ALLOWED_CALLER, otherAudience)));

        assertEnvelope(response, SecurityErrorCode.SERVICE_UNAUTHORIZED);
    }

    @Test
    @DisplayName("허용 목록에 없는 호출자의 서비스 토큰은 403 · SECURITY_005 · WWW-Authenticate 없음")
    void internalPathWithDeniedCaller() throws Exception {
        MockHttpServletResponse response = perform(get(InternalSecuritySmokeController.PATH)
            .header(HttpHeaders.AUTHORIZATION, TestServiceTokens.bearer(InternalSecuritySmokeController.DENIED_CALLER, serviceId())));

        assertEnvelope(response, SecurityErrorCode.FORBIDDEN);
        assertWwwAuthenticate(response, null);
    }

    /** 위 401 · 403 케이스와 함께라야 "허용된 호출자만 통과" 가 된다. 본문은 인증 주체의 호출 서비스 ID 다. */
    @Test
    @DisplayName("허용된 호출자의 서비스 토큰은 통과한다 — 200 · 주체는 호출 서비스")
    void internalPathWithAllowedCaller() throws Exception {
        MockHttpServletResponse response = perform(get(InternalSecuritySmokeController.PATH)
            .header(HttpHeaders.AUTHORIZATION, TestServiceTokens.bearer(InternalSecuritySmokeController.ALLOWED_CALLER, serviceId())));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo(InternalSecuritySmokeController.ALLOWED_CALLER);
    }

    @Test
    @DisplayName("서비스 토큰으로 사용자 경로를 부르면 401 · SECURITY_003 — 서비스 토큰은 /api/** 에서 쓰이지 않는다")
    void serviceTokenOnUserPath() throws Exception {
        MockHttpServletResponse response = perform(get(PUBLIC_PATH)
            .header(HttpHeaders.AUTHORIZATION, TestServiceTokens.bearer(InternalSecuritySmokeController.ALLOWED_CALLER, serviceId())));

        assertEnvelope(response, SecurityErrorCode.TOKEN_INVALID);
    }

    // ── 인가 선언 누락 가드 ──────────────────────────────────────

    /**
     * URL 단계는 {@code /api/**} 를 열어 두고(default-allow) internal 체인은 "서비스 토큰이 있다" 까지만 보므로, 메서드의 {@code @PreAuthorize} 가
     * 빠지면 공개 API 가 되거나 서비스 5종 누구나 부르는 internal API 가 된다 ({@code security-guide.md} §5-1 · §9-4). 서비스의 실제 매핑을 모두 돈다.
     *
     * <ul>
     *   <li>{@code /internal/} — 메서드(없으면 클래스)의 {@code @PreAuthorize} 식이 {@code hasAuthority('SERVICE_<id>')} 또는
     *       {@code hasAnyAuthority('SERVICE_<id>', ...)} <b>그 자체</b>여야 한다. {@code isAuthenticated()} · {@code permitAll()} · {@code or} 로 넓힌 식은 실패다.</li>
     *   <li>{@code /api/v1/admin} 아래 — 식이 운영자 권한을 필요조건으로 해야 한다({@link AuthorizationExpressions#requiresOperatorAuthority}). ArchUnit R2 는 패키지
     *       ({@code adapter.in.admin})로 보므로, 어드민 핸들러를 다른 패키지({@code adapter.in.web.admin} 등)에 두거나 상속으로 받으면 비켜 간다.
     *       여기는 실제 매핑 경로로 보아 패키지 · 상속과 무관하다.</li>
     *   <li>그 밖의 {@code /api/} — {@code @PreAuthorize} 가 있으면 된다(식은 {@code permitAll()} · {@code isAuthenticated()} · {@code hasRole(...)} 등 자유).</li>
     * </ul>
     *
     * <p>지금 서비스에는 실제 핸들러가 없어 빈 목록으로 통과하지 않도록, 스모크 핸들러가 검사 대상에 들어 있는지도 본다.
     */
    @Test
    @DisplayName("모든 /internal 핸들러는 허용 호출자 hasAuthority('SERVICE_…') 를, /api/v1/admin 핸들러는 운영자 권한(AdminAuthority 상수)을, 모든 /api 핸들러는 @PreAuthorize 를 선언한다")
    void handlersDeclareAuthorization() {
        RequestMappingHandlerMapping handlerMapping = applicationContext.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);

        AuthorizationScan scan = scanHandlers(handlerMapping.getHandlerMethods());

        assertThat(scan.checkedInternalPatterns()).as("스모크 핸들러가 검사 대상에 들어 있다 — 빈 목록이라 통과한 것이 아니다").contains(InternalSecuritySmokeController.PATH);
        assertThat(scan.violations()).as("허용 호출자 · 어드민 권한 · 인가 선언이 없는 핸들러 (security-guide.md §5-1 · §9-4)").isEmpty();
    }

    /** 인가 선언 누락 가드의 판정. 컨텍스트 없이 security-core 테스트가 매핑을 직접 만들어 시험할 수 있게 떼어 둔다. */
    static AuthorizationScan scanHandlers(Map<RequestMappingInfo, HandlerMethod> handlerMethods) {
        List<String> checkedInternalPatterns = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        handlerMethods.forEach((mapping, handler) -> {
            Set<String> patterns = mapping.getPatternValues();
            boolean internal = patterns.stream().anyMatch(pattern -> pattern.startsWith(INTERNAL_PREFIX));
            boolean admin = patterns.stream().anyMatch(pattern -> pattern.equals(ADMIN_API_PREFIX) || pattern.startsWith(ADMIN_API_PREFIX + "/"));
            boolean api = patterns.stream().anyMatch(pattern -> pattern.startsWith(API_PREFIX));
            if (!internal && !api) {
                return;
            }
            PreAuthorize declared = declaredPreAuthorize(handler);
            String handlerName = handler.getBeanType().getName() + "#" + handler.getMethod().getName() + " " + patterns;
            String found = declared == null ? "@PreAuthorize 없음" : "@PreAuthorize(\"" + declared.value() + "\")";
            if (internal) {
                checkedInternalPatterns.addAll(patterns);
                if (declared == null || !AuthorizationExpressions.isServiceCallersOnly(declared.value())) {
                    violations.add(handlerName + " -> " + found + " — hasAuthority('SERVICE_<호출 서비스 ID>') · hasAnyAuthority(...) 로 부를 수 있는 서비스만 적으세요");
                }
            } else if (admin) {
                if (declared == null || !AuthorizationExpressions.requiresOperatorAuthority(declared.value())) {
                    violations.add(handlerName + " -> " + found + " — 어드민 경로는 AdminAuthority 상수(hasAuthority('ADMIN_<기능>_READ|MANAGE') · hasAnyRole('ADMIN','OWNER','MANAGER','STAFF'))"
                        + " 를 머리로, 좁힐 때만 and ... 로 고치세요 — hasRole('ADMIN') 은 #588 부터 받지 않습니다");
                }
            } else if (declared == null) {
                violations.add(handlerName + " -> " + found + " — 메서드에 인가 식(permitAll() · isAuthenticated() · hasRole(...))을 적으세요");
            }
        });
        return new AuthorizationScan(List.copyOf(checkedInternalPatterns), List.copyOf(violations));
    }

    /** {@link #scanHandlers} 결과 — 검사한 internal 경로(빈 검사 방지)와 위반 목록. */
    record AuthorizationScan(List<String> checkedInternalPatterns, List<String> violations) {
    }

    // ── 관리자 감사 선언 누락 가드 (#163) ────────────────────────

    /**
     * 아직 감사 선언을 붙이지 않은 어드민 쓰기 핸들러 — 키 {@code "클래스 단순 이름#메서드"}, 값은 맡은 이슈 표기({@code "#163 PR-B"} 처럼 {@code #<번호>} 를 포함).
     * 서비스 스모크가 override 한다 ({@code security-guide.md} §11-4). 비어 있지 않으면 WARN 을 남긴다. 아래는 실패다 — 이슈 표기에 {@code #<번호>} 가 없음,
     * 이미 선언했거나 없어진 항목(목록이 저절로 줄어든다), 한 항목이 쓰기 핸들러 둘 이상(같은 이름의 오버로드)과 맞음(새 핸들러가 묻어 가지 않게).
     */
    protected Map<String, String> auditDeclarationPending() {
        return Map.of();
    }

    /**
     * 어드민 쓰기가 감사 줄 없이 늘어나지 않게 한다 ({@code security-guide.md} §11-4). {@code /api/v1/admin} 아래 매핑 중 POST · PUT · PATCH · DELETE
     * (메서드 조건이 없으면 모든 메서드라 쓰기로 본다) 핸들러는 {@link AdminAudited} 또는 {@link AdminAuditExempt} 를 선언해야 한다. 실제 매핑을 보므로
     * 패키지 · 상속과 무관하다.
     */
    @Test
    @DisplayName("/api/v1/admin 의 쓰기(POST · PUT · PATCH · DELETE) 핸들러는 @AdminAudited 또는 @AdminAuditExempt 를 선언한다")
    void adminWriteHandlersDeclareAudit() {
        RequestMappingHandlerMapping handlerMapping = applicationContext.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        Map<String, String> pending = auditDeclarationPending();

        AuditScan scan = scanAuditDeclarations(handlerMapping.getHandlerMethods(), pending);

        if (!pending.isEmpty()) {
            LoggerFactory.getLogger(getClass()).warn("감사 선언 대기(auditDeclarationPending) {}건 — 맡은 이슈에서 선언을 붙이고 목록에서 지우세요: {}", pending.size(), pending);
        }
        assertThat(scan.violations()).as("감사 선언이 없는 어드민 쓰기 핸들러 · 정리할 대기 항목 (security-guide.md §11-4)").isEmpty();
    }

    /** 감사 선언 누락 가드의 판정. 컨텍스트 없이 security-core 테스트가 매핑을 직접 만들어 시험할 수 있게 떼어 둔다. */
    static AuditScan scanAuditDeclarations(Map<RequestMappingInfo, HandlerMethod> handlerMethods, Map<String, String> pending) {
        List<String> violations = new ArrayList<>();
        Map<String, Set<Method>> pendingMatched = new HashMap<>();

        pending.entrySet().stream()
            .filter(entry -> entry.getValue() == null || !ISSUE_REFERENCE.matcher(entry.getValue()).find())
            .map(Map.Entry::getKey)
            .sorted()
            .forEach(key -> violations.add(key + " [대기] → 이슈 표기가 없습니다 — auditDeclarationPending() 의 값에 맡은 이슈(\"#163 PR-B\" 처럼 #<번호>)를 적으세요"));

        handlerMethods.forEach((mapping, handler) -> {
            Set<String> patterns = mapping.getPatternValues();
            boolean admin = patterns.stream().anyMatch(pattern -> pattern.equals(ADMIN_API_PREFIX) || pattern.startsWith(ADMIN_API_PREFIX + "/"));
            if (!admin || !isWrite(mapping)) {
                return;
            }
            String key = handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();
            String where = key + " " + patterns;
            AdminAudited audited = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), AdminAudited.class);
            AdminAuditExempt exempt = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), AdminAuditExempt.class);
            if (pending.containsKey(key)) {
                pendingMatched.computeIfAbsent(key, ignored -> new HashSet<>()).add(handler.getMethod());
                if (audited != null || exempt != null) {
                    violations.add(where + " → 이미 선언했습니다 — auditDeclarationPending() 에서 \"" + key + "\" 를 지우세요");
                }
                return;
            }
            if (audited != null && exempt != null) {
                violations.add(where + " → @AdminAudited 와 @AdminAuditExempt 를 같이 붙였습니다 — 하나만 남기세요");
            } else if (audited == null && exempt == null) {
                violations.add(where + " → 감사 선언 없음 — @AdminAudited(action = \"<RESOURCE>_<VERB>\") 를 붙이고 Facade 에서 감사 줄을 쓰거나, "
                    + "저장 없음 · 본인 상태만 바꾸면 @AdminAuditExempt(reason = \"...\") 를 붙이세요");
            } else if (audited != null && !audited.action().matches(AUDIT_ACTION_REGEX)) {
                violations.add(where + " → @AdminAudited(action = \"" + audited.action() + "\") — 행위 이름은 " + AUDIT_ACTION_REGEX + " 형식(<RESOURCE>_<VERB>)으로 고치세요");
            } else if (exempt != null && exempt.reason().isBlank()) {
                violations.add(where + " → @AdminAuditExempt 의 reason 이 비었습니다 — 면제 이유를 적으세요");
            }
        });
        pending.keySet().stream()
            .filter(key -> !pendingMatched.containsKey(key))
            .sorted()
            .forEach(key -> violations.add(key + " [없음] → 이 이름의 어드민 쓰기 핸들러가 없습니다 — auditDeclarationPending() 에서 지우세요"));
        pendingMatched.entrySet().stream()
            .filter(entry -> entry.getValue().size() > 1)
            .map(Map.Entry::getKey)
            .sorted()
            .forEach(key -> violations.add(key + " [쓰기 핸들러 " + pendingMatched.get(key).size() + "개] → 대기 항목 하나가 같은 이름의 핸들러 여럿과 맞습니다 — "
                + "새 핸들러가 대기 목록에 묻어 가지 않게 선언을 붙이거나 메서드 이름을 나누세요"));
        return new AuditScan(List.copyOf(violations));
    }

    /** POST · PUT · PATCH · DELETE 중 하나를 받거나, 메서드 조건이 없어 모든 메서드를 받는 매핑. */
    private static boolean isWrite(RequestMappingInfo mapping) {
        Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();
        return methods.isEmpty() || methods.stream().anyMatch(WRITE_METHODS::contains);
    }

    /** {@link #scanAuditDeclarations} 결과 — 위반 목록. */
    record AuditScan(List<String> violations) {
    }

    // ── 구성 ─────────────────────────────────────────────────────

    /**
     * 노출 설정은 actuator 의 1차 방어다. URL 규칙이 노출된 health 외를 막아도, {@code heapdump} · {@code loggers} 를 목록에 넣는 실수는 여기서 잡는다
     * (auth-service 의 힙에는 개인키가 있다). 쉼표 문자열 · YAML 목록 어느 쪽으로 적어도 바인딩된 최종 값을 본다.
     */
    @Test
    @DisplayName("actuator 웹 노출은 정확히 health 하나다")
    void actuatorExposesHealthOnly() {
        Set<String> include = Binder.get(environment).bind("management.endpoints.web.exposure.include", Bindable.setOf(String.class)).orElse(Set.of());

        assertThat(include).containsExactly("health");
    }

    /**
     * 테스트는 초기화기({@link SecurityTestPropertiesInitializer})가 키를 맨 앞에 넣어 yml 의 값이 가려진다. 운영 배선이 빠지거나 바뀌지 않았는지
     * {@code application.yml} 원본 속성 소스의 풀리지 않은 원문을 본다 ({@code security-guide.md} §5-3).
     */
    @Test
    @DisplayName("application.yml 이 공개키를 환경변수 JWT_PUBLIC_KEY · JWT_PUBLIC_KEY_PREVIOUS 로 받는다")
    void applicationYmlWiresPublicKeyPlaceholders() {
        List<PropertySource<?>> applicationYml = environment.getPropertySources().stream()
            .filter(source -> source instanceof OriginTrackedMapPropertySource && source.getName().contains("[application.yml]"))
            .toList();

        assertThat(applicationYml).as("application.yml 속성 소스").hasSize(1);
        assertThat(applicationYml.getFirst().getProperty("security.jwt.public-key")).isEqualTo("${JWT_PUBLIC_KEY}");
        assertThat(applicationYml.getFirst().getProperty("security.jwt.previous-public-key")).isEqualTo("${JWT_PUBLIC_KEY_PREVIOUS:}");
    }

    /** 테스트는 초기화기가 비밀을 맨 앞에 넣어 yml 의 값이 가려진다. 운영 배선은 원본 속성 소스의 풀리지 않은 원문으로 본다 ({@code security-guide.md} §9). */
    @Test
    @DisplayName("application.yml 이 서비스 토큰 비밀을 환경변수 INTERNAL_JWT_SECRET · INTERNAL_JWT_SECRET_PREVIOUS 로, 서비스 ID 를 spring.application.name 으로 받는다")
    void applicationYmlWiresInternalTokenPlaceholders() {
        List<PropertySource<?>> applicationYml = environment.getPropertySources().stream()
            .filter(source -> source instanceof OriginTrackedMapPropertySource && source.getName().contains("[application.yml]"))
            .toList();

        assertThat(applicationYml).as("application.yml 속성 소스").hasSize(1);
        assertThat(applicationYml.getFirst().getProperty("security.internal.service-id")).isEqualTo("${spring.application.name}");
        assertThat(applicationYml.getFirst().getProperty("security.internal.secret")).isEqualTo("${INTERNAL_JWT_SECRET}");
        assertThat(applicationYml.getFirst().getProperty("security.internal.previous-secret")).isEqualTo("${INTERNAL_JWT_SECRET_PREVIOUS:}");
    }

    /** 다른 서비스는 Feign 대상 이름(= Eureka 서비스 ID)을 aud 로 보낸다. 이 서비스가 기대하는 aud 가 그 이름과 같아야 한다. */
    @Test
    @DisplayName("서비스 토큰의 서비스 ID 는 spring.application.name(Eureka 서비스 ID)이다")
    void internalServiceIdIsApplicationName() {
        assertThat(applicationContext.getBean(InternalTokenProperties.class).serviceId()).isEqualTo(serviceId());
    }

    /** 발급기는 auth-service 만 붙인다. 테스트 키에 개인키가 들어 있어 잘못 import 해도 기동하므로 빈 유무로 본다. */
    @Test
    @DisplayName("발급기(JwtIssuer)는 발급하는 서비스에만 있다")
    void issuerOnlyWhereExpected() {
        assertThat(applicationContext.getBeanNamesForType(JwtIssuer.class)).hasSize(issuerExpected() ? 1 : 0);
    }

    // ── 도우미 ────────────────────────────────────────────────────

    protected final MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    /** 메서드의 {@code @PreAuthorize}, 없으면 클래스의 것. 메서드 보안과 같은 우선순위다. 메타 애노테이션도 찾는다. */
    private static PreAuthorize declaredPreAuthorize(HandlerMethod handler) {
        PreAuthorize onMethod = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
    }

    /** 이 서비스의 ID — {@code spring.application.name}. 다른 서비스가 보내는 서비스 토큰의 {@code aud} 다. */
    protected final String serviceId() {
        return environment.getRequiredProperty("spring.application.name");
    }

    /** 발급기({@link TestTokens#issuer})로 지금 발급한 access 토큰. 서명 키는 컨텍스트의 공개키와 한 쌍이다. */
    protected static String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }
}
