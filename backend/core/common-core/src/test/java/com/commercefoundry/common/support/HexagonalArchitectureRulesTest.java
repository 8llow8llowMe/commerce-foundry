package com.commercefoundry.common.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
 * 규칙 자체의 시험. 인터페이스의 default 테스트는 서비스 코드 소스만 import 하므로, 여기서는 규칙(공개 상수)을 준수 · 위반 예시 패키지({@code archfixture})에 직접 건다.
 * 예시 패키지는 서비스와 같은 {@code domainlayer.<context>.{adapter,application,domain}} 이름을 흉내 낸다 — {@code goods} 가 {@code catalog} 를 쓴다.
 * 위반 예시는 규칙마다 걸려야 하는 의존(origin → target)이 다 걸리고, 그 밖의 클래스는 걸리지 않는지 본다.
 */
class HexagonalArchitectureRulesTest {

    private static final String FIXTURE = "com.commercefoundry.common.support.archfixture";
    private static final JavaClasses COMPLIANT = new ClassFileImporter().importPackages(FIXTURE + ".compliant");
    private static final JavaClasses VIOLATING = new ClassFileImporter().importPackages(FIXTURE + ".violating");

    static Stream<ArchRule> rules() {
        return Stream.of(
            HexagonalArchitectureRules.CONTROLLERS_DEPEND_ON_USE_CASES_ONLY,
            HexagonalArchitectureRules.APPLICATION_DOES_NOT_DEPEND_ON_ADAPTERS,
            HexagonalArchitectureRules.PROCESSORS_DO_NOT_DEPEND_ON_ADAPTERS,
            HexagonalArchitectureRules.DOMAIN_DEPENDS_ON_NO_OUTER_LAYER_OR_FRAMEWORK,
            HexagonalArchitectureRules.OUT_PORTS_DO_NOT_EXPOSE_INFO,
            HexagonalArchitectureRules.ENTRY_ADAPTERS_DO_NOT_SHARE_TYPES,
            HexagonalArchitectureRules.INBOUND_ADAPTERS_DO_NOT_USE_OUTBOUND_ADAPTERS,
            HexagonalArchitectureRules.CROSS_CONTEXT_DEPENDENCIES_FOLLOW_ALLOW_LIST,
            HexagonalArchitectureRules.PRESENTERS_ONLY_CONVERT
        );
    }

    @Test
    @DisplayName("예시 패키지가 import 됐다 — 빈 import 로 통과하지 않는다")
    void fixturesAreImported() {
        assertThat(COMPLIANT).hasSizeGreaterThan(15);
        assertThat(VIOLATING).hasSizeGreaterThan(15);
    }

    @ParameterizedTest
    @MethodSource("rules")
    @DisplayName("준수 예시는 모든 규칙을 통과한다 — 문서화된 예외 셋 · Facade 의 중첩 타입 · 컨트롤러의 UseCase 입력 타입(model) · 다른 컨텍스트 Processor · domain model · 예외 번역"
        + " · adapter/in/support 공용 함수 포함")
    void compliantFixturesPass(ArchRule rule) {
        assertThatCode(() -> rule.check(COMPLIANT)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("H1 컨트롤러가 Processor · Presenter · Repository · Facade · Info 를 쓰면 실패한다 — controller 패키지 밖 @RestController 도 본다")
    void controllersUsingMoreThanUseCasesFail() {
        assertThat(violations(HexagonalArchitectureRules.CONTROLLERS_DEPEND_ON_USE_CASES_ONLY))
            .allSatisfy(detail -> assertThat(detail).containsAnyOf(".LeakyWebController.", ".MisplacedWebController."))
            .anySatisfy(detail -> assertThat(detail).contains(".LeakyWebController.", ".GoodsProcessor", "UseCase 만 호출합니다"))
            .anySatisfy(detail -> assertThat(detail).contains(".LeakyWebController.", ".GoodsWebPresenter"))
            .anySatisfy(detail -> assertThat(detail).contains(".LeakyWebController.", ".GoodsJpaRepository"))
            .anySatisfy(detail -> assertThat(detail).contains(".LeakyWebController.describe", "info.GoodsInfo"))
            .anySatisfy(detail -> assertThat(detail).contains(".MisplacedWebController.", ".GoodsWebFacade"));
    }

    @Test
    @DisplayName("H2 Processor 의 Response DTO · out-port 의 Feign 타입 · Facade 의 Repository 는 문서화된 예외가 아니라 실패한다")
    void applicationDependingOnAdaptersOutsideExceptionsFails() {
        assertThat(violations(HexagonalArchitectureRules.APPLICATION_DOES_NOT_DEPEND_ON_ADAPTERS))
            .allSatisfy(detail -> assertThat(detail).containsAnyOf(".GoodsProcessor.", ".GoodsClientPort.", ".GoodsWebFacade."))
            .anySatisfy(detail -> assertThat(detail).contains(".GoodsProcessor.", ".GoodsResponse", "out-port 뒤에 숨기고"))
            .anySatisfy(detail -> assertThat(detail).contains(".GoodsClientPort.", ".GoodsFeignResponse"))
            .anySatisfy(detail -> assertThat(detail).contains(".GoodsWebFacade.", ".GoodsJpaRepository"));
    }

    @Test
    @DisplayName("H3 Processor 가 Response DTO 를 만들면 실패한다")
    void processorBuildingResponseFails() {
        assertThat(violations(HexagonalArchitectureRules.PROCESSORS_DO_NOT_DEPEND_ON_ADAPTERS))
            .isNotEmpty()
            .allSatisfy(detail -> assertThat(detail).contains(".GoodsProcessor.", ".GoodsResponse"));
    }

    @Test
    @DisplayName("H4 domain 이 Info · Spring 에 의존하면 실패한다 — 다른 컨텍스트 domain 은 H8 이 본다")
    void domainDependingOnOuterLayerOrFrameworkFails() {
        assertThat(violations(HexagonalArchitectureRules.DOMAIN_DEPENDS_ON_NO_OUTER_LAYER_OR_FRAMEWORK))
            .allSatisfy(detail -> assertThat(detail).contains("model.Goods.").doesNotContain(".Catalog>"))
            .anySatisfy(detail -> assertThat(detail).contains(".GoodsInfo"))
            .anySatisfy(detail -> assertThat(detail).contains("org.springframework.util.Assert"));
    }

    @Test
    @DisplayName("H5 out-port 가 Info 를 반환하면 실패한다")
    void outPortExposingInfoFails() {
        assertThat(violations(HexagonalArchitectureRules.OUT_PORTS_DO_NOT_EXPOSE_INFO))
            .isNotEmpty()
            .allSatisfy(detail -> assertThat(detail).contains(".GoodsReaderPort.", ".GoodsInfo"));
    }

    @Test
    @DisplayName("H6 어드민 진입이 고객 응답 DTO 를 쓰면 실패한다")
    void entryAdaptersSharingTypesFail() {
        assertThat(violations(HexagonalArchitectureRules.ENTRY_ADAPTERS_DO_NOT_SHARE_TYPES))
            .isNotEmpty()
            .allSatisfy(detail -> assertThat(detail).contains(".GoodsAdminPresenter.", "web.dto.response.GoodsResponse", "진입마다 DTO 를 따로"));
    }

    @Test
    @DisplayName("H7 컨트롤러가 Repository 를 쓰면 실패한다")
    void inboundAdapterUsingOutboundAdapterFails() {
        assertThat(violations(HexagonalArchitectureRules.INBOUND_ADAPTERS_DO_NOT_USE_OUTBOUND_ADAPTERS))
            .isNotEmpty()
            .allSatisfy(detail -> assertThat(detail).contains(".LeakyWebController.", ".GoodsJpaRepository"));
    }

    @Test
    @DisplayName("H8 Processor 끼리의 주입 · 다른 컨텍스트 out-port · domain 필드 · exception 패키지 밖의 다른 컨텍스트 예외는 허용 목록 밖이라 실패한다")
    void crossContextDependenciesOutsideAllowListFail() {
        assertThat(violations(HexagonalArchitectureRules.CROSS_CONTEXT_DEPENDENCIES_FOLLOW_ALLOW_LIST))
            .allSatisfy(detail -> assertThat(detail).containsAnyOf(".GoodsProcessor.", ".GoodsWebFacade.", "model.Goods.", ".GoodsWebAdvice.", ".GoodsExceptionHandler."))
            .anySatisfy(detail -> assertThat(detail).contains(".GoodsProcessor.", ".CatalogProcessor", "용도별 메서드를 자기 Facade 에서"))
            .anySatisfy(detail -> assertThat(detail).contains(".GoodsWebFacade.", ".CatalogReaderPort"))
            .anySatisfy(detail -> assertThat(detail).contains("model.Goods.", "model.Catalog"))
            .anySatisfy(detail -> assertThat(detail).contains(".GoodsWebAdvice.", "exception.CatalogException"))
            .anySatisfy(detail -> assertThat(detail).contains(".GoodsExceptionHandler.", ".CatalogReaderPort"))
            .noneSatisfy(detail -> assertThat(detail).contains(".GoodsExceptionHandler.", "exception.CatalogException>"));
    }

    @Test
    @DisplayName("H9 Presenter 가 Processor · QueryResult 를 쓰면 실패한다")
    void presenterUsingServiceOrPortFails() {
        assertThat(violations(HexagonalArchitectureRules.PRESENTERS_ONLY_CONVERT))
            .allSatisfy(detail -> assertThat(detail).contains(".GoodsWebPresenter."))
            .anySatisfy(detail -> assertThat(detail).contains(".GoodsProcessor"))
            .anySatisfy(detail -> assertThat(detail).contains(".GoodsQueryResult"));
    }

    private static List<String> violations(ArchRule rule) {
        return rule.evaluate(VIOLATING).getFailureReport().getDetails();
    }
}
