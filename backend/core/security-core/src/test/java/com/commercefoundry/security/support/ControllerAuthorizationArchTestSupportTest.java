package com.commercefoundry.security.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.security.internal.InternalTokenFormat;
import com.commercefoundry.security.internal.client.InternalFeignClientConfiguration;
import com.commercefoundry.security.support.archfixture.FakeEnableFeignClients;
import com.commercefoundry.security.support.archfixture.compliant.adapter.in.web.CompliantWebController;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 규칙 자체의 시험. 지원 클래스는 서비스 코드 소스만 import 하므로, 여기서는 규칙(공개 상수)을 준수 · 위반 예시 패키지({@code archfixture})에 직접 건다.
 * 예시 패키지는 서비스와 같은 {@code adapter.in.web · admin · internal} 이름을 흉내 낸다. 컴포넌트 스캔 대상이 아니다.
 */
class ControllerAuthorizationArchTestSupportTest {

    private static final String FIXTURE = "com.commercefoundry.security.support.archfixture";
    private static final JavaClasses COMPLIANT = new ClassFileImporter().importPackages(FIXTURE + ".compliant");
    private static final JavaClasses VIOLATING = new ClassFileImporter().importPackages(FIXTURE + ".violating");
    /** OpenFeign 없이 같은 규칙을 대역 애노테이션으로 건다 ({@link FakeEnableFeignClients}). */
    private static final ArchRule DEFAULT_FEIGN_CONFIGURATION =
        ControllerAuthorizationArchTestSupport.enableFeignClientsWithoutDefaultConfiguration(FakeEnableFeignClients.class.getName());

    static Stream<ArchRule> rules() {
        return Stream.of(
            ControllerAuthorizationArchTestSupport.CONTROLLERS_RESIDE_IN_ADAPTER_IN_PACKAGES,
            ControllerAuthorizationArchTestSupport.CONTROLLERS_DECLARE_MAPPINGS_DIRECTLY,
            ControllerAuthorizationArchTestSupport.NO_GLOBAL_FEIGN_REQUEST_CUSTOMIZER_COMPONENTS,
            ControllerAuthorizationArchTestSupport.CUSTOMER_AND_ADMIN_HANDLERS_DECLARE_PRE_AUTHORIZE,
            ControllerAuthorizationArchTestSupport.ADMIN_HANDLERS_REQUIRE_ADMIN_ROLE,
            ControllerAuthorizationArchTestSupport.INTERNAL_HANDLERS_ALLOW_SERVICE_CALLERS_ONLY,
            ControllerAuthorizationArchTestSupport.NO_REQUEST_CONTEXT_HOLDER,
            ControllerAuthorizationArchTestSupport.NO_GLOBAL_FEIGN_REQUEST_CUSTOMIZER_BEANS,
            DEFAULT_FEIGN_CONFIGURATION
        );
    }

    @ParameterizedTest
    @MethodSource("rules")
    @DisplayName("준수 예시는 모든 규칙을 통과한다 — 메타 애노테이션 · 괄호 안 or · 클래스의 허용 호출자 · @RestControllerAdvice · webhook · 매핑 없는 인터페이스 · 비컴포넌트 인터셉터 포함")
    void compliantFixturesPass(ArchRule rule) {
        assertThatCode(() -> rule.check(COMPLIANT)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("R1 고객 · 어드민 핸들러에 메서드 @PreAuthorize 가 없으면 실패한다 — 클래스 선언은 대신하지 못한다")
    void customerAndAdminHandlersWithoutPreAuthorizeFail() {
        assertThat(violations(ControllerAuthorizationArchTestSupport.CUSTOMER_AND_ADMIN_HANDLERS_DECLARE_PRE_AUTHORIZE))
            .hasSize(3)
            .anySatisfy(detail -> assertThat(detail).contains("MissingPreAuthorizeWebController#classLevelOnly", "@PreAuthorize 가 없습니다"))
            .anySatisfy(detail -> assertThat(detail).contains("MissingPreAuthorizeWebController#forgotten"))
            .anySatisfy(detail -> assertThat(detail).contains("WidenedAdminController#missing"))
            .noneSatisfy(detail -> assertThat(detail).contains("UnrestrictedInternalController"));
    }

    @Test
    @DisplayName("R2 어드민 핸들러가 permitAll() · isAuthenticated() · or 로 넓힌 식 · 선언 없음이면 실패한다")
    void adminHandlersWithoutAdminRoleFail() {
        assertThat(violations(ControllerAuthorizationArchTestSupport.ADMIN_HANDLERS_REQUIRE_ADMIN_ROLE))
            .hasSize(5)
            .anySatisfy(detail -> assertThat(detail).contains("WidenedAdminController#open", "@PreAuthorize(\"permitAll()\")", "hasRole('ADMIN')"))
            .anySatisfy(detail -> assertThat(detail).contains("WidenedAdminController#member"))
            .anySatisfy(detail -> assertThat(detail).contains("WidenedAdminController#widened"))
            .anySatisfy(detail -> assertThat(detail).contains("WidenedAdminController#precedence"))
            .anySatisfy(detail -> assertThat(detail).contains("WidenedAdminController#missing", "@PreAuthorize 없음"));
    }

    @Test
    @DisplayName("R3 internal 핸들러가 허용 호출자 식 그 자체가 아니면 실패한다")
    void internalHandlersWithoutServiceCallersFail() {
        assertThat(violations(ControllerAuthorizationArchTestSupport.INTERNAL_HANDLERS_ALLOW_SERVICE_CALLERS_ONLY))
            .hasSize(3)
            .anySatisfy(detail -> assertThat(detail).contains("UnrestrictedInternalController#anyService", "isAuthenticated()"))
            .anySatisfy(detail -> assertThat(detail).contains("UnrestrictedInternalController#widened"))
            .anySatisfy(detail -> assertThat(detail).contains("UnrestrictedInternalController#missing", "@PreAuthorize 없음"));
    }

    @Test
    @DisplayName("R4 RequestContextHolder 를 쓰면 실패한다")
    void requestContextHolderFails() {
        assertThat(violations(ControllerAuthorizationArchTestSupport.NO_REQUEST_CONTEXT_HOLDER))
            .isNotEmpty()
            .allSatisfy(detail -> assertThat(detail).contains("UserTokenRelay", "RequestContextHolder"));
    }

    @Test
    @DisplayName("R5 RequestInterceptor · Capability(구현 타입 포함)를 반환하는 @Bean 이 있으면 실패한다")
    void feignRequestCustomizerBeansFail() {
        assertThat(violations(ControllerAuthorizationArchTestSupport.NO_GLOBAL_FEIGN_REQUEST_CUSTOMIZER_BEANS))
            .hasSize(2)
            .anySatisfy(detail -> assertThat(detail).contains("GlobalFeignConfig.userTokenRelay()", "feign.RequestInterceptor"))
            .anySatisfy(detail -> assertThat(detail).contains("GlobalFeignConfig.serviceTokenEverywhere(", "InternalServiceTokenCapability"));
    }

    @Test
    @DisplayName("R5 RequestInterceptor · Capability 구현 클래스가 컴포넌트(@Component · @Configuration 등)면 실패한다 — 컴포넌트가 아닌 구현은 통과")
    void feignRequestCustomizerComponentsFail() {
        assertThat(violations(ControllerAuthorizationArchTestSupport.NO_GLOBAL_FEIGN_REQUEST_CUSTOMIZER_COMPONENTS))
            .hasSize(2)
            .anySatisfy(detail -> assertThat(detail).contains("violating.config.ScannedInterceptor", "컴포넌트"))
            .anySatisfy(detail -> assertThat(detail).contains("violating.config.ScannedCapability"));
    }

    @Test
    @DisplayName("R6 @EnableFeignClients 에 defaultConfiguration 이 있으면 실패한다 — 합성 애노테이션으로 단 경우 · 그 합성 애노테이션 자체도")
    void defaultFeignConfigurationFails() {
        assertThat(violations(DEFAULT_FEIGN_CONFIGURATION))
            .hasSize(3)
            .anySatisfy(detail -> assertThat(detail).contains("violating.config.GlobalFeignConfig ", "defaultConfiguration"))
            .anySatisfy(detail -> assertThat(detail).contains("violating.config.MetaGlobalFeignConfig "))
            .anySatisfy(detail -> assertThat(detail).contains("violating.config.EnableAllFeignClients "));
    }

    @Test
    @DisplayName("R0 adapter.in.web · admin · internal · webhook 밖의 컨트롤러는 실패한다 — @RestControllerAdvice 는 대상이 아니다")
    void controllersOutsideAdapterInPackagesFail() {
        assertThat(violations(ControllerAuthorizationArchTestSupport.CONTROLLERS_RESIDE_IN_ADAPTER_IN_PACKAGES))
            .hasSize(2)
            .anySatisfy(detail -> assertThat(detail).contains("violating.adapter.in.rest.MisplacedRestController", "adapter.in.admin"))
            .anySatisfy(detail -> assertThat(detail).contains("violating.presentation.PresentationController"));
    }

    @Test
    @DisplayName("R7 매핑 메서드를 물려주는 인터페이스 · 상위 클래스를 구현 · 상속한 컨트롤러는 실패한다 — R1 · R2 가 보지 못하는 핸들러")
    void controllersInheritingMappingsFail() {
        assertThat(violations(ControllerAuthorizationArchTestSupport.CONTROLLERS_DECLARE_MAPPINGS_DIRECTLY))
            .hasSize(2)
            .anySatisfy(detail -> assertThat(detail).contains("InheritedWebController", "ProductApi#list"))
            .anySatisfy(detail -> assertThat(detail).contains("InheritedAdminController", "AbstractAdminController#inherited"));
        assertThat(violations(ControllerAuthorizationArchTestSupport.CUSTOMER_AND_ADMIN_HANDLERS_DECLARE_PRE_AUTHORIZE))
            .as("R7 이 없으면 놓치는 핸들러다 — R1 은 상속받은 매핑을 보지 못한다")
            .noneSatisfy(detail -> assertThat(detail).contains("Inherited"));
    }

    @Test
    @DisplayName("실패 메시지는 고치는 법과 문서 절을 담는다")
    void failureMessageCarriesTheFix() {
        assertThatThrownBy(() -> ControllerAuthorizationArchTestSupport.CUSTOMER_AND_ADMIN_HANDLERS_DECLARE_PRE_AUTHORIZE.check(VIOLATING))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("permitAll()")
            .hasMessageContaining("security-guide.md §5");
    }

    /**
     * 지원 클래스는 애플리케이션 클래스의 코드 소스 하나만 import 한다. security-core 본체로 시험하면 본체 클래스만 들어오고 테스트 · 픽스처는 빠진다.
     * 본체의 {@link InternalFeignClientConfiguration}(클라이언트마다 붙이는 Capability 빈)은 R5 에 걸린다 — 서비스가 core 를 import 하지 않는 이유다.
     */
    @Test
    @DisplayName("import 범위는 애플리케이션 클래스의 코드 소스 하나다 — 같은 모듈의 테스트 · 다른 코드 소스는 섞이지 않는다")
    void importsCodeSourceOfApplicationClassOnly() {
        JavaClasses imported = new SecurityCoreMainArchitecture().serviceClasses();

        assertThat(imported.contain(InternalTokenFormat.class)).isTrue();
        assertThat(imported.contain(InternalFeignClientConfiguration.class)).isTrue();
        assertThat(imported.contain(ControllerAuthorizationArchTestSupportTest.class)).isFalse();
        assertThat(imported.contain(CompliantWebController.class)).isFalse();
        assertThat(imported.contain(ControllerAuthorizationArchTestSupport.class)).as("testFixtures 는 다른 코드 소스다").isFalse();
        assertThat(ControllerAuthorizationArchTestSupport.NO_GLOBAL_FEIGN_REQUEST_CUSTOMIZER_BEANS.evaluate(imported).hasViolation()).isTrue();
    }

    private static List<String> violations(ArchRule rule) {
        return rule.evaluate(VIOLATING).getFailureReport().getDetails();
    }

    /** private 이라 JUnit 이 테스트 클래스로 찾지 않는다 — 본체에는 R5 에 걸리는 클래스가 있어 그대로 돌면 실패한다. */
    private static final class SecurityCoreMainArchitecture extends ControllerAuthorizationArchTestSupport {

        @Override
        protected Class<?> applicationClass() {
            return InternalTokenFormat.class;
        }
    }
}
