package com.commercefoundry.security.support;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.lang.reflect.Array;
import java.net.URL;
import java.security.CodeSource;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 컨트롤러 인가 선언 · 전역 Feign 설정의 정적 규칙(ArchUnit). 서비스 코드만 보고 빌드를 실패시킨다 — 컨텍스트를 띄우지 않는다.
 * 서비스는 애플리케이션 클래스 하나만 알려 주고 상속한다.
 *
 * <pre>{@code
 * class DeliveryServiceArchitectureTest extends ControllerAuthorizationArchTestSupport {
 *
 *     @Override
 *     protected Class<?> applicationClass() {
 *         return DeliveryServiceApplication.class;
 *     }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@link ResourceServerSmokeTestSupport#handlersDeclareAuthorization} 는 실제 MVC 매핑을 <b>경로</b>로 보는 런타임 가드다. 여기는 <b>패키지</b>로 본다 —
 *       어드민 핸들러가 {@code hasRole('ADMIN')} 을 요구하는지, 고객 핸들러가 클래스가 아니라 메서드마다 선언했는지는 경로만으로 알 수 없다.
 *       둘은 서로를 대신하지 않는다 ({@code security-guide.md} §5 · §9-4).</li>
 *   <li>import 범위는 애플리케이션 클래스의 코드 소스(서비스의 {@code build/classes/java/main}) 하나다. core jar · 테스트 클래스 · 다른 서비스가 섞이지 않는다 —
 *       security-core 의 {@code InternalFeignClientConfiguration}(Capability 빈)이나 테스트 전용 스모크 핸들러가 규칙에 걸리지 않는다.</li>
 *   <li>컨트롤러가 아직 없는 서비스도 통과한다(규칙마다 {@code allowEmptyShould}). 대신 애플리케이션 클래스가 import 됐는지 단언해
 *       "아무것도 import 하지 못해 통과" 를 막는다.</li>
 *   <li>규칙은 공개 상수다 — security-core 가 위반 · 준수 예시로 규칙 자체를 시험하고, 서비스가 규칙을 더할 때 {@link #serviceClasses()} 를 쓴다.</li>
 * </ul>
 */
public abstract class ControllerAuthorizationArchTestSupport {

    /** Feign 은 서비스마다 클래스패스에 있을 수도 없을 수도 있어 이름으로 비교한다. */
    static final String ENABLE_FEIGN_CLIENTS = "org.springframework.cloud.openfeign.EnableFeignClients";
    private static final List<String> FEIGN_REQUEST_CUSTOMIZERS = List.of("feign.RequestInterceptor", "feign.Capability");
    private static final String REQUEST_CONTEXT_HOLDER = "org.springframework.web.context.request.RequestContextHolder";

    private static final String CUSTOMER_PACKAGE = "..adapter.in.web..";
    private static final String ADMIN_PACKAGE = "..adapter.in.admin..";
    private static final String INTERNAL_PACKAGE = "..adapter.in.internal..";

    /** 서비스 테스트 클래스마다 한 번만 import 한다 — 규칙 테스트가 같은 결과를 나눠 쓴다. */
    private static final Map<Class<?>, JavaClasses> IMPORTED = new ConcurrentHashMap<>();

    /**
     * R1. 고객 · 어드민 핸들러는 <b>메서드마다</b> {@code @PreAuthorize} 를 선언한다. URL 단계가 {@code /api/**} 를 열어 두므로(default-allow)
     * 빠뜨리면 공개 API 가 된다. 클래스 선언으로 대신하지 않는다 — 빠뜨린 메서드가 리뷰에서 보이게 한다 ({@code security-guide.md} §5).
     */
    public static final ArchRule CUSTOMER_AND_ADMIN_HANDLERS_DECLARE_PRE_AUTHORIZE = methods()
        .that(handlerMethodsIn("adapter.in.web · adapter.in.admin 컨트롤러의 핸들러 메서드", CUSTOMER_PACKAGE, ADMIN_PACKAGE))
        .should(declarePreAuthorizeOnMethod())
        .because("URL 단계는 /api/** 를 열어 둔다(default-allow) — 공개면 permitAll(), 로그인 필요면 isAuthenticated(), 어드민이면 hasRole('ADMIN') 을"
            + " 메서드에 적는다 (security-guide.md §5)")
        .allowEmptyShould(true);

    /** R2. 어드민 핸들러는 ADMIN 이 필요조건이다 — {@code hasRole('ADMIN')} 또는 {@code hasRole('ADMIN') and ...}. 판정은 {@link AuthorizationExpressions#requiresAdminRole}. */
    public static final ArchRule ADMIN_HANDLERS_REQUIRE_ADMIN_ROLE = methods()
        .that(handlerMethodsIn("adapter.in.admin 컨트롤러의 핸들러 메서드", ADMIN_PACKAGE))
        .should(requireAdminRole())
        .because("어드민 API 는 ADMIN 만 부른다 — permitAll() · isAuthenticated() · or 로 넓힌 식은 고객에게 열린다 (security-guide.md §5)")
        .allowEmptyShould(true);

    /**
     * R3. internal 핸들러는 허용 호출자({@code hasAuthority('SERVICE_<id>')} · {@code hasAnyAuthority(...)}) 그 자체를 선언한다(메서드, 없으면 클래스).
     * internal 체인은 "서비스 토큰이 있다" 까지만 보므로 빠뜨리면 서비스 5종 누구나 부른다 ({@code security-guide.md} §9-4).
     */
    public static final ArchRule INTERNAL_HANDLERS_ALLOW_SERVICE_CALLERS_ONLY = methods()
        .that(handlerMethodsIn("adapter.in.internal 컨트롤러의 핸들러 메서드", INTERNAL_PACKAGE))
        .should(allowServiceCallersOnly())
        .because("internal 체인은 서비스 토큰 유무만 본다 — 부를 수 있는 서비스를 hasAuthority('SERVICE_<id>') · hasAnyAuthority(...) 로 적는다 (security-guide.md §9-4)")
        .allowEmptyShould(true);

    /** R4. 현재 요청의 헤더(사용자 토큰)를 {@code RequestContextHolder} 로 꺼내 internal 호출에 싣지 않는다. */
    public static final ArchRule NO_REQUEST_CONTEXT_HOLDER = noClasses()
        .should().dependOnClassesThat().haveFullyQualifiedName(REQUEST_CONTEXT_HOLDER)
        .because("사용자 Authorization 을 internal Feign 으로 넘기지 않는다 — 사용자 식별이 필요하면 memberId 를 본문 · 파라미터로 넘긴다 (coding-conventions.md §10)")
        .allowEmptyShould(true);

    /** R5. 모든 Feign 클라이언트에 붙는 {@code RequestInterceptor} · {@code Capability} 빈을 만들지 않는다. */
    public static final ArchRule NO_GLOBAL_FEIGN_REQUEST_CUSTOMIZER_BEANS = methods()
        .that().areMetaAnnotatedWith(Bean.class)
        .should(notReturnFeignRequestCustomizer())
        .because("전역 인터셉터 · Capability 는 외부 API(토스 등)에도 실려 사용자 토큰 · 서비스 토큰이 새거나 그쪽 Authorization 을 덮어쓴다 —"
            + " internal 클라이언트는 @FeignClient(configuration = InternalFeignClientConfiguration.class) 로 클라이언트마다 붙인다 (coding-conventions.md §10)")
        .allowEmptyShould(true);

    /** R6. {@code @EnableFeignClients(defaultConfiguration = ...)} 로 모든 클라이언트에 설정을 붙이지 않는다. */
    public static final ArchRule ENABLE_FEIGN_CLIENTS_WITHOUT_DEFAULT_CONFIGURATION = enableFeignClientsWithoutDefaultConfiguration(ENABLE_FEIGN_CLIENTS);

    /** 이 서비스의 {@code @SpringBootApplication} 클래스. 이 클래스가 있는 코드 소스(서비스의 main 클래스 디렉터리)만 import 한다. */
    protected abstract Class<?> applicationClass();

    // ── 규칙 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("서비스 main 클래스만 import 한다 — 애플리케이션 클래스는 있고 테스트 클래스는 없다 (빈 import 로 통과하지 않는다)")
    void importsServiceMainClassesOnly() {
        JavaClasses imported = serviceClasses();

        assertThat(imported.contain(getClass())).as("테스트 클래스가 섞이지 않는다").isFalse();
        assertThat(imported.contain(ControllerAuthorizationArchTestSupport.class)).as("security-core 클래스가 섞이지 않는다").isFalse();
    }

    @Test
    @DisplayName("adapter.in.web · adapter.in.admin 핸들러는 메서드마다 @PreAuthorize 를 선언한다")
    void customerAndAdminHandlersDeclarePreAuthorize() {
        CUSTOMER_AND_ADMIN_HANDLERS_DECLARE_PRE_AUTHORIZE.check(serviceClasses());
    }

    @Test
    @DisplayName("adapter.in.admin 핸들러의 @PreAuthorize 는 hasRole('ADMIN') 을 필요조건으로 한다")
    void adminHandlersRequireAdminRole() {
        ADMIN_HANDLERS_REQUIRE_ADMIN_ROLE.check(serviceClasses());
    }

    @Test
    @DisplayName("adapter.in.internal 핸들러는 허용 호출자 hasAuthority('SERVICE_…') 그 자체를 선언한다")
    void internalHandlersAllowServiceCallersOnly() {
        INTERNAL_HANDLERS_ALLOW_SERVICE_CALLERS_ONLY.check(serviceClasses());
    }

    @Test
    @DisplayName("RequestContextHolder 를 쓰지 않는다 — 사용자 토큰을 internal 로 넘기지 않는다")
    void noRequestContextHolder() {
        NO_REQUEST_CONTEXT_HOLDER.check(serviceClasses());
    }

    @Test
    @DisplayName("Feign RequestInterceptor · Capability 를 반환하는 @Bean 이 없다 — 전역 Feign 설정 금지")
    void noGlobalFeignRequestCustomizerBeans() {
        NO_GLOBAL_FEIGN_REQUEST_CUSTOMIZER_BEANS.check(serviceClasses());
    }

    @Test
    @DisplayName("@EnableFeignClients 에 defaultConfiguration 을 두지 않는다")
    void enableFeignClientsWithoutDefaultConfiguration() {
        ENABLE_FEIGN_CLIENTS_WITHOUT_DEFAULT_CONFIGURATION.check(serviceClasses());
    }

    // ── 도우미 ────────────────────────────────────────────────────

    /** 서비스 main 클래스. 서비스가 규칙을 더할 때도 이것을 쓴다. 애플리케이션 클래스가 들어 있지 않으면 실패한다. */
    protected final JavaClasses serviceClasses() {
        Class<?> applicationClass = applicationClass();
        JavaClasses imported = IMPORTED.computeIfAbsent(applicationClass, ControllerAuthorizationArchTestSupport::importCodeSourceOf);
        assertThat(imported.contain(applicationClass)).as("애플리케이션 클래스 %s 가 import 됐다 — 빈 import 로 통과하지 않는다", applicationClass.getName()).isTrue();
        return imported;
    }

    /** 클래스가 로드된 코드 소스(Gradle 테스트에서는 {@code build/classes/java/main} 디렉터리, jar 면 그 jar) 하나만 import 한다. */
    private static JavaClasses importCodeSourceOf(Class<?> type) {
        CodeSource codeSource = type.getProtectionDomain().getCodeSource();
        assertThat(codeSource).as("%s 의 코드 소스", type.getName()).isNotNull();
        URL location = codeSource.getLocation();
        return new ClassFileImporter().importUrl(location);
    }

    /** {@code annotationName} 을 받는 것은 security-core 테스트가 OpenFeign 없이 같은 규칙을 시험하게 하려는 것이다. 서비스는 {@link #ENABLE_FEIGN_CLIENTS_WITHOUT_DEFAULT_CONFIGURATION} 을 쓴다. */
    static ArchRule enableFeignClientsWithoutDefaultConfiguration(String annotationName) {
        return classes()
            .that().areAnnotatedWith(annotationName)
            .should(haveNoDefaultFeignConfiguration(annotationName))
            .because("defaultConfiguration 은 외부 API 클라이언트에도 붙는다 — 설정은 @FeignClient(configuration = ...) 로 클라이언트마다 붙인다 (coding-conventions.md §10)")
            .allowEmptyShould(true);
    }

    /** {@code @Controller}(메타 포함 — {@code @RestController}) 클래스가 패키지 안에 있고, 메서드에 {@code @RequestMapping}(메타 포함 — {@code @GetMapping} 등)이 있다. */
    private static DescribedPredicate<JavaMethod> handlerMethodsIn(String description, String... packages) {
        DescribedPredicate<JavaClass> inPackages = JavaClass.Predicates.resideInAnyPackage(packages);
        return DescribedPredicate.describe(description, method -> method.getOwner().isMetaAnnotatedWith(Controller.class)
            && inPackages.test(method.getOwner())
            && method.isMetaAnnotatedWith(RequestMapping.class));
    }

    private static ArchCondition<JavaMethod> declarePreAuthorizeOnMethod() {
        return new ArchCondition<>("메서드에 @PreAuthorize 를 선언한다") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean declared = method.isMetaAnnotatedWith(PreAuthorize.class);
                events.add(new SimpleConditionEvent(method, declared, handlerName(method) + " 에 @PreAuthorize 가 없습니다 — 메서드에 인가 식을 적으세요"));
            }
        };
    }

    private static ArchCondition<JavaMethod> requireAdminRole() {
        return new ArchCondition<>("@PreAuthorize 가 hasRole('ADMIN') 을 필요조건으로 한다") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                Optional<String> expression = preAuthorizeValue(method);
                boolean satisfied = expression.filter(AuthorizationExpressions::requiresAdminRole).isPresent();
                String declared = expression.map(value -> "@PreAuthorize(\"" + value + "\")").orElse("@PreAuthorize 없음");
                events.add(new SimpleConditionEvent(method, satisfied,
                    handlerName(method) + " -> " + declared + " — hasRole('ADMIN') 또는 hasRole('ADMIN') and ... 로 고치세요"));
            }
        };
    }

    private static ArchCondition<JavaMethod> allowServiceCallersOnly() {
        return new ArchCondition<>("허용 호출자 hasAuthority('SERVICE_…') · hasAnyAuthority(...) 그 자체를 선언한다") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                Optional<String> expression = preAuthorizeValue(method).or(() -> preAuthorizeValue(method.getOwner()));
                boolean satisfied = expression.filter(AuthorizationExpressions::isServiceCallersOnly).isPresent();
                String declared = expression.map(value -> "@PreAuthorize(\"" + value + "\")").orElse("@PreAuthorize 없음");
                events.add(new SimpleConditionEvent(method, satisfied,
                    handlerName(method) + " -> " + declared + " — @PreAuthorize(\"hasAuthority('SERVICE_<호출 서비스 ID>')\") 로 부를 수 있는 서비스만 적으세요"));
            }
        };
    }

    private static ArchCondition<JavaMethod> notReturnFeignRequestCustomizer() {
        return new ArchCondition<>("Feign RequestInterceptor · Capability 를 반환하지 않는다") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                JavaClass returnType = method.getRawReturnType();
                boolean customizer = FEIGN_REQUEST_CUSTOMIZERS.stream().anyMatch(returnType::isAssignableTo);
                events.add(new SimpleConditionEvent(method, !customizer,
                    method.getFullName() + " " + method.getSourceCodeLocation() + " 가 " + returnType.getName() + " 빈을 만듭니다 — 전역 Feign 설정이 됩니다"));
            }
        };
    }

    private static ArchCondition<JavaClass> haveNoDefaultFeignConfiguration(String annotationName) {
        return new ArchCondition<>("defaultConfiguration 을 비워 둔다") {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                Optional<Object> defaultConfiguration = type.getAnnotationOfType(annotationName).get("defaultConfiguration");
                boolean empty = defaultConfiguration.map(value -> !value.getClass().isArray() || Array.getLength(value) == 0).orElse(true);
                events.add(new SimpleConditionEvent(type, empty,
                    type.getName() + " " + type.getSourceCodeLocation() + " 의 @EnableFeignClients 에 defaultConfiguration 이 있습니다 — 지우고 클라이언트마다 붙이세요"));
            }
        };
    }

    /** 직접 붙은 {@code @PreAuthorize}, 없으면 메타 애노테이션(합성 애노테이션) 안의 것. 스모크의 {@code findMergedAnnotation} 과 같은 탐색 순서다. */
    private static Optional<String> preAuthorizeValue(HasAnnotations<?> element) {
        return findPreAuthorize(element.getAnnotations(), new HashSet<>());
    }

    private static Optional<String> findPreAuthorize(Set<? extends JavaAnnotation<?>> annotations, Set<String> visited) {
        for (JavaAnnotation<?> annotation : annotations) {
            if (annotation.getRawType().isEquivalentTo(PreAuthorize.class)) {
                return annotation.get("value").map(String::valueOf);
            }
        }
        for (JavaAnnotation<?> annotation : annotations) {
            JavaClass annotationType = annotation.getRawType();
            if (visited.add(annotationType.getName())) {
                Optional<String> found = findPreAuthorize(annotationType.getAnnotations(), visited);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static String handlerName(JavaMethod method) {
        return method.getOwner().getName() + "#" + method.getName() + " " + method.getSourceCodeLocation();
    }
}
