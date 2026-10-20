package com.commercefoundry.common.support;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;

/**
 * 헥사고날 계층 의존 규칙(ArchUnit, H1~H12). {@code architecture-guide.md} §2 · §3 · §4 의 의존 방향을 서비스 main 클래스에 걸어 빌드를 실패시킨다 — 컨텍스트를 띄우지 않는다.
 * 서비스는 기존 {@code <Svc>ArchitectureTest} 에 {@code implements} 만 더한다. JUnit 이 인터페이스의 default {@code @Test} 메서드를 찾아 실행한다.
 *
 * <pre>{@code
 * class DeliveryServiceArchitectureTest extends ControllerAuthorizationArchTestSupport implements HexagonalArchitectureRules {
 *
 *     @Override
 *     public Class<?> applicationClass() {
 *         return DeliveryServiceApplication.class;
 *     }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>대상은 {@code domainlayer} 아래 클래스뿐이다. {@code global}(설정 · 프로퍼티)과 애플리케이션 클래스는 계층이 아니라 보지 않는다.</li>
 *   <li>패키지 이름으로 계층을 고른다 — {@code adapter.in.<진입>.{controller,dto,presenter,exception}}, {@code adapter.in.{support,scheduler}},
 *       {@code application.{port.in,port.out,service,service.processor,mapper,info,command,model,exception}}, {@code domain}. 패키지를 비켜 간 클래스는 규칙이 보지 못한다.
 *       컨트롤러는 {@code @Controller}(메타 포함)로도, Facade · Processor · ExceptionHandler · ValidationMessage 는 바깥 클래스 이름 접미사로도 고른다.</li>
 *   <li>import 범위는 security-core 의 {@code ControllerAuthorizationArchTestSupport} 와 같다(애플리케이션 클래스의 코드 소스 하나). 그 지원 클래스의 import 를
 *       나눠 쓰지 않는다 — common-core 픽스처가 security-core 에 의존하게 되기 때문이다. 서비스 테스트 클래스마다 한 번 더 import 한다(수백 ms).</li>
 *   <li>규칙에 걸리는 클래스가 없는 서비스(payment — 아직 {@code domainlayer} 없음)도 통과한다({@code allowEmptyShould}). 대신 애플리케이션 클래스가
 *       import 됐는지 단언해 "아무것도 import 하지 못해 통과" 를 막는다.</li>
 *   <li>규칙은 공개 상수다 — common-core 가 위반 · 준수 예시({@code archfixture})로 규칙 자체를 시험한다.</li>
 *   <li>트랜잭션 경계, 상태 전이가 도메인 메서드로만 일어나는지, 저장 흐름(domain → entity → save), out-port 이름, ID 생성 위치는 기계로 보지 않는다 — 리뷰가 본다.</li>
 * </ul>
 */
public interface HexagonalArchitectureRules {

    /**
     * H1. 컨트롤러는 application 중 {@code port/in} 의 UseCase, 그 입력 타입({@code application/command} · {@code application/model} — 정렬 enum 같은
     * {@code @RequestParam}), 검증 메시지 상수({@code application/exception} 의 {@code *ValidationMessage})만 쓴다. Facade · Processor · out-port · Info · 다른 예외 타입 ·
     * domain 을 직접 쓰지 않고, Presenter 로 응답을 만들지 않으며 out-adapter(리포지토리 · Feign · PG)를 부르지 않는다. 같은 진입의 요청 · 응답 DTO 는 쓴다.
     * {@code *ValidationMessage} 는 컴파일 상수라 보통 인라인돼 의존으로 보이지 않지만, 인라인에 기대지 않고 명시적으로 허용한다.
     */
    ArchRule CONTROLLERS_DEPEND_ON_USE_CASES_ONLY = classes()
        .that(Support.controllers())
        .should(Support.notDependOn(
            "application 은 port/in · command · model · *ValidationMessage 만 · domain · presenter · adapter/out 은 쓰지 않는다",
            (origin, target) -> Support.in(target, Support.APPLICATION) && !Support.isControllerApplicationType(target)
                || Support.in(target, Support.DOMAIN) || Support.in(target, Support.PRESENTER) || Support.in(target, Support.ADAPTER_OUT),
            "컨트롤러는 *WebUseCase · *AdminUseCase · *InternalUseCase 만 호출합니다 — 조합은 Facade, 응답 변환은 Facade 가 주입한 Presenter 가 합니다."
                + " 요청 값은 진입 DTO · command · model 로 받고 domain 타입은 Processor 가 만듭니다"))
        .because("Controller → UseCase → Facade → Processor → Port 흐름을 지킨다. UseCase 입력 타입(command · model)과 *ValidationMessage 는 허용 — 정렬 enum 같은"
            + " @RequestParam (architecture-guide.md §3 Controller)")
        .allowEmptyShould(true);

    /**
     * H2. application 은 adapter 에 의존하지 않는다. 문서화된 예외 셋만 허용한다 — ① {@code port/in} → {@code adapter/in/*}/dto (UseCase 의 인자 · 반환),
     * ② Facade({@code application/service} 의 {@code *Facade}) → {@code adapter/in/*}/{presenter,dto}, ③ {@code application/mapper} → {@code adapter/out/persistence/entity}.
     */
    ArchRule APPLICATION_DOES_NOT_DEPEND_ON_ADAPTERS = classes()
        .that().resideInAPackage(Support.APPLICATION)
        .should(Support.notDependOn(
            "문서화된 예외(port/in → in DTO · Facade → presenter · in DTO · mapper → entity) 밖의 adapter 에 의존하지 않는다",
            (origin, target) -> Support.in(target, Support.ADAPTER) && !Support.documentedAdapterException(origin, target),
            "out-adapter 타입(Feign 래퍼 · PG DTO · Repository · Entity)은 out-port 뒤에 숨기고 QueryResult · domain 으로 받으세요."
                + " Response DTO · Presenter 는 Facade 만 씁니다"))
        .because("application 은 adapter 구현 타입에 의존하지 않는다 — 예외는 port/in 반환 DTO, Facade 의 Presenter, mapper 의 Entity 셋뿐이다 (architecture-guide.md §3 Port / Adapter)")
        .allowEmptyShould(true);

    /** H3. Processor 는 어떤 adapter 에도 의존하지 않는다 — Response DTO 를 만들지 않고, Presenter · Entity · Feign 타입을 모른다. H2 와 겹치지만 고치는 법이 달라 따로 둔다. */
    ArchRule PROCESSORS_DO_NOT_DEPEND_ON_ADAPTERS = noClasses()
        .that().resideInAPackage(Support.PROCESSOR)
        .should().dependOnClassesThat().resideInAPackage(Support.ADAPTER)
        .because("Processor 는 Info · domain model · application model 을 반환하고 Response DTO 를 만들지 않는다 — 응답 변환은 Presenter, 외부 타입은 out-port 뒤에 둔다"
            + " (architecture-guide.md §3 Processor)")
        .allowEmptyShould(true);

    /** H4. domain 은 application · adapter · Spring · JPA 에 의존하지 않는다. shared-commerce(Money 등) · JDK 는 쓴다. */
    ArchRule DOMAIN_DEPENDS_ON_NO_OUTER_LAYER_OR_FRAMEWORK = noClasses()
        .that().resideInAPackage(Support.DOMAIN)
        .should().dependOnClassesThat().resideInAnyPackage(Support.APPLICATION, Support.ADAPTER, "org.springframework..", "jakarta.persistence..", "org.hibernate..")
        .because("domain model 은 가장 안쪽이다 — 프레임워크 · 바깥 계층을 모르고, 영속성 표현은 adapter/out/persistence/entity 가 맡는다 (architecture-guide.md §2 · §5)")
        .allowEmptyShould(true);

    /** H5. out-port 는 Info 를 노출하지 않는다 — 반환은 {@code QueryResult} 또는 domain model 이다. */
    ArchRule OUT_PORTS_DO_NOT_EXPOSE_INFO = noClasses()
        .that().resideInAPackage(Support.PORT_OUT)
        .should().dependOnClassesThat().resideInAPackage(Support.INFO)
        .because("out-port 반환은 application/port/out/query 의 *QueryResult 또는 domain model 이다 — Info 는 Processor 가 만든다 (architecture-guide.md §3 Port / Adapter · §4)")
        .allowEmptyShould(true);

    /**
     * H6. 고객 · 어드민 · 서비스 간 · 웹훅 진입은 서로의 DTO · Presenter · 컨트롤러를 쓰지 않는다. 같이 쓰는 변환 함수는 {@code adapter/in/support} 에 두되,
     * support 는 어느 진입의 DTO({@code adapter/in/*}/dto)도 모른다 — 공용 타입(MoneyItem 등)만 만든다.
     */
    ArchRule ENTRY_ADAPTERS_DO_NOT_SHARE_TYPES = classes()
        .that().resideInAPackage(Support.ADAPTER_IN)
        .should(Support.notDependOn(
            "다른 진입(web · admin · internal · webhook)의 타입에 의존하지 않고, support 는 진입 DTO 에 의존하지 않는다",
            (origin, target) -> Support.entryOf(origin).flatMap(entry -> Support.entryOf(target).filter(other -> !other.equals(entry))).isPresent()
                || Support.in(origin, Support.ENTRY_SUPPORT) && Support.in(target, Support.ENTRY_DTO),
            "진입마다 DTO 를 따로 두세요 — 같은 상품이라도 고객 · 어드민 · 서비스 간 응답의 필드가 다릅니다. 공용 변환 함수는 adapter/in/support 로 옮기고,"
                + " support 는 진입 DTO 대신 공용 타입을 돌려주세요"))
        .because("고객 · 어드민 · 서비스 간 API 는 진입 패키지부터 나누고 DTO 를 공유하지 않는다 — 공유하면 한쪽 필드가 다른 응답으로 샌다 (architecture-guide.md §2)")
        .allowEmptyShould(true);

    /** H7. in-adapter(컨트롤러 · DTO · Presenter · 웹훅 · 스케줄러)는 out-adapter(Repository · Entity · Feign · PG)를 쓰지 않는다 — UseCase 를 거친다. */
    ArchRule INBOUND_ADAPTERS_DO_NOT_USE_OUTBOUND_ADAPTERS = noClasses()
        .that().resideInAPackage(Support.ADAPTER_IN)
        .should().dependOnClassesThat().resideInAPackage(Support.ADAPTER_OUT)
        .because("진입이 리포지토리 · 외부 클라이언트를 직접 부르면 Facade 의 트랜잭션 · Processor 의 검증을 비켜 간다 — UseCase 를 호출하세요 (architecture-guide.md §3)")
        .allowEmptyShould(true);

    /**
     * H8. 같은 서비스의 다른 컨텍스트에서 쓸 수 있는 것은 그 컨텍스트의 Processor(Facade 만 주입)와 domain model(Facade · Processor 가 인자로 주고받는다) 둘뿐이다.
     * domain 은 다른 컨텍스트 domain 을 필드로 갖지 않고 아이디로만 가리킨다. 그 Processor 가 던진 예외({@code application/exception})는 부른 컨텍스트의
     * {@code adapter/in/*}/exception 의 {@code *ExceptionHandler} 만 받아 자기 코드로 번역한다 — advice 범위가 자기 컨트롤러 패키지라서다.
     * Processor 는 최상위 {@code *Processor} 클래스만이다 — 그 중첩 타입 · processor 패키지의 다른 클래스는 쓰지 않는다(돌려받는 것은 domain model 이다).
     */
    ArchRule CROSS_CONTEXT_DEPENDENCIES_FOLLOW_ALLOW_LIST = classes()
        .that().resideInAPackage(Support.DOMAINLAYER)
        .should(Support.notDependOn(
            "다른 컨텍스트는 Processor(Facade 에서) · domain model(Facade · Processor 에서) · 예외(ExceptionHandler 에서)만 쓴다",
            (origin, target) -> Support.crossContext(origin, target) && !Support.allowedAcrossContexts(origin, target),
            "다른 컨텍스트의 UseCase · Facade · Presenter · Info · Port · Adapter · Mapper · Entity · Repository 를 쓰지 마세요."
                + " 그 컨텍스트 Processor 의 용도별 메서드를 자기 Facade 에서 부르고, 돌려받은 domain model 만 자기 Processor 에 넘깁니다."
                + " 그 컨텍스트 예외는 자기 adapter.in.<진입>.exception 의 *ExceptionHandler 에서만 번역합니다"))
        .because("다른 컨텍스트의 포트 · 리포지토리를 직접 부르면 그 컨텍스트의 조회 규칙(숨김 · 정렬 · 캐시)을 비켜 간다 (architecture-guide.md §3 컨텍스트 간 의존 규칙)")
        .allowEmptyShould(true);

    /** H9. Presenter 는 {@code Info → Response} 변환만 한다 — Facade · Processor · port(UseCase · out-port · QueryResult)를 쓰지 않는다. */
    ArchRule PRESENTERS_ONLY_CONVERT = noClasses()
        .that().resideInAPackage(Support.PRESENTER)
        .should().dependOnClassesThat().resideInAnyPackage(Support.SERVICE, Support.PORT)
        .because("Presenter 는 Info · domain model 을 Response 로 바꾸는 경계다 — 조회 · 조합은 Facade 가 하고, QueryResult 는 Processor 가 Info 로 바꾼다"
            + " (architecture-guide.md §3 Presenter · §4)")
        .allowEmptyShould(true);

    /**
     * H10. 진입 전체({@code adapter/in} — 컨트롤러 · DTO · Presenter · ExceptionHandler · support · scheduler · webhook)는 Facade · Processor({@code application/service}) ·
     * out-port({@code application/port/out}, QueryResult 포함) · mapper 에 의존하지 않는다 — UseCase 를 거친다. H1 · H9 는 컨트롤러 · Presenter 에 더 좁은 규칙을 건다.
     */
    ArchRule ENTRY_ADAPTERS_GO_THROUGH_USE_CASES = noClasses()
        .that().resideInAPackage(Support.ADAPTER_IN)
        .should().dependOnClassesThat().resideInAnyPackage(Support.SERVICE, Support.PORT_OUT, Support.MAPPER)
        .because("진입(스케줄러 · 웹훅 포함)이 Facade · Processor · out-port 를 직접 부르면 UseCase 계약과 Facade 의 트랜잭션 · 조합을 비켜 간다 —"
            + " *UseCase(port/in)를 호출하세요 (architecture-guide.md §3)")
        .allowEmptyShould(true);

    /** H11. Facade 는 out-port({@code application/port/out}, QueryResult 포함)를 쓰지 않는다 — 포트 호출과 QueryResult → Info 변환은 Processor 가 한다. */
    ArchRule FACADES_DO_NOT_USE_OUT_PORTS = classes()
        .that(Support.facades())
        .should(Support.notDependOn(
            "out-port · QueryResult 에 의존하지 않는다",
            (origin, target) -> Support.in(target, Support.PORT_OUT),
            "out-port 호출을 Processor 로 옮기고 Facade 는 Processor 가 돌려준 Info · domain model 을 조합하세요"))
        .because("Facade 는 Processor · Presenter 를 조합하는 오케스트레이터다 — facade · presenter 쪽에는 QueryResult 가 번지지 않게 한다 (architecture-guide.md §3 Facade · §4)")
        .allowEmptyShould(true);

    /**
     * H12. application · domain 은 전송 타입(Feign {@code feign..} · {@code org.springframework.cloud.openfeign..}, 공통 응답 {@code com.commercefoundry.common.dto..})을
     * 모른다. 예외: {@code common.dto.metadata}(도메인 enum 의 {@code CodeNameDescribable}), UseCase({@code port/in}) · Facade 의 응답 봉투 {@code common.dto.Response}.
     * {@code SliceResponse} · {@code PageResponse} 는 persistence-core({@code com.commercefoundry.persistence.dto})라 이 규칙의 대상이 아니다.
     */
    ArchRule APPLICATION_AND_DOMAIN_DO_NOT_USE_TRANSPORT_TYPES = classes()
        .that().resideInAnyPackage(Support.APPLICATION, Support.DOMAIN)
        .should(Support.notDependOn(
            "Feign · 공통 응답 DTO(common.dto — metadata · port/in · Facade 의 Response 제외)에 의존하지 않는다",
            (origin, target) -> Support.isTransportType(target) && !Support.allowedTransportType(origin, target),
            "Feign 타입은 adapter/out/client 뒤에, 응답 DTO(MoneyItem · Response 등)는 Presenter · 컨트롤러 쪽에 두세요 — Processor · domain 은 Money · enum 그대로 다룹니다"))
        .because("application · domain 이 전송 형식을 알면 원격 계약 · 응답 모양이 바뀔 때 안쪽 계층이 같이 깨진다 (architecture-guide.md §3 Port / Adapter · Presenter)")
        .allowEmptyShould(true);

    /** 이 서비스의 {@code @SpringBootApplication} 클래스. 이 클래스가 있는 코드 소스(서비스의 main 클래스 디렉터리)만 import 한다. */
    Class<?> applicationClass();

    // ── 규칙 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("헥사고날 규칙은 서비스 main 클래스만 import 한다 — 애플리케이션 클래스는 있고 테스트 · common-core 클래스는 없다")
    default void hexagonalRulesImportServiceMainClassesOnly() {
        JavaClasses imported = hexagonalTargetClasses();

        assertThat(imported.contain(getClass())).as("테스트 클래스가 섞이지 않는다").isFalse();
        assertThat(imported.contain(HexagonalArchitectureRules.class)).as("common-core 픽스처 클래스가 섞이지 않는다").isFalse();
    }

    @Test
    @DisplayName("H1 컨트롤러는 application 중 port/in UseCase · 입력 타입(command · model) · *ValidationMessage 만 쓴다 — domain · Presenter · out-adapter 금지")
    default void controllersDependOnUseCasesOnly() {
        CONTROLLERS_DEPEND_ON_USE_CASES_ONLY.check(hexagonalTargetClasses());
    }

    @Test
    @DisplayName("H2 application 은 adapter 에 의존하지 않는다 — port/in → in DTO · Facade → presenter · in DTO · mapper → entity 만 예외")
    default void applicationDoesNotDependOnAdapters() {
        APPLICATION_DOES_NOT_DEPEND_ON_ADAPTERS.check(hexagonalTargetClasses());
    }

    @Test
    @DisplayName("H3 Processor 는 adapter 에 의존하지 않는다 — Response DTO 를 만들지 않는다")
    default void processorsDoNotDependOnAdapters() {
        PROCESSORS_DO_NOT_DEPEND_ON_ADAPTERS.check(hexagonalTargetClasses());
    }

    @Test
    @DisplayName("H4 domain 은 application · adapter · Spring · JPA 에 의존하지 않는다")
    default void domainDependsOnNoOuterLayerOrFramework() {
        DOMAIN_DEPENDS_ON_NO_OUTER_LAYER_OR_FRAMEWORK.check(hexagonalTargetClasses());
    }

    @Test
    @DisplayName("H5 out-port 는 Info 에 의존하지 않는다 — QueryResult · domain 을 반환한다")
    default void outPortsDoNotExposeInfo() {
        OUT_PORTS_DO_NOT_EXPOSE_INFO.check(hexagonalTargetClasses());
    }

    @Test
    @DisplayName("H6 고객 · 어드민 · internal · webhook 진입은 서로의 타입을 쓰지 않고, support 는 진입 DTO 를 쓰지 않는다")
    default void entryAdaptersDoNotShareTypes() {
        ENTRY_ADAPTERS_DO_NOT_SHARE_TYPES.check(hexagonalTargetClasses());
    }

    @Test
    @DisplayName("H7 in-adapter 는 out-adapter(Repository · Entity · Feign · PG)를 쓰지 않는다")
    default void inboundAdaptersDoNotUseOutboundAdapters() {
        INBOUND_ADAPTERS_DO_NOT_USE_OUTBOUND_ADAPTERS.check(hexagonalTargetClasses());
    }

    @Test
    @DisplayName("H8 다른 컨텍스트는 Processor(Facade 에서) · domain model(Facade · Processor 에서) · 예외(ExceptionHandler 에서)만 쓴다")
    default void crossContextDependenciesFollowAllowList() {
        CROSS_CONTEXT_DEPENDENCIES_FOLLOW_ALLOW_LIST.check(hexagonalTargetClasses());
    }

    @Test
    @DisplayName("H9 Presenter 는 Facade · Processor · port 를 쓰지 않는다 — Info → Response 변환만")
    default void presentersOnlyConvert() {
        PRESENTERS_ONLY_CONVERT.check(hexagonalTargetClasses());
    }

    @Test
    @DisplayName("H10 진입 전체(스케줄러 · 웹훅 · ExceptionHandler · support 포함)는 Facade · Processor · out-port · mapper 를 쓰지 않는다")
    default void entryAdaptersGoThroughUseCases() {
        ENTRY_ADAPTERS_GO_THROUGH_USE_CASES.check(hexagonalTargetClasses());
    }

    @Test
    @DisplayName("H11 Facade 는 out-port · QueryResult 를 쓰지 않는다 — Processor 를 거친다")
    default void facadesDoNotUseOutPorts() {
        FACADES_DO_NOT_USE_OUT_PORTS.check(hexagonalTargetClasses());
    }

    @Test
    @DisplayName("H12 application · domain 은 Feign · 공통 응답 DTO 를 쓰지 않는다 — metadata · UseCase · Facade 의 Response 만 예외")
    default void applicationAndDomainDoNotUseTransportTypes() {
        APPLICATION_AND_DOMAIN_DO_NOT_USE_TRANSPORT_TYPES.check(hexagonalTargetClasses());
    }

    // ── 도우미 ────────────────────────────────────────────────────

    /** 서비스 main 클래스. 애플리케이션 클래스가 들어 있지 않으면 실패한다. */
    default JavaClasses hexagonalTargetClasses() {
        Class<?> applicationClass = applicationClass();
        JavaClasses imported = HexagonalArchitectureImports.of(applicationClass);
        assertThat(imported.contain(applicationClass)).as("애플리케이션 클래스 %s 가 import 됐다 — 빈 import 로 통과하지 않는다", applicationClass.getName()).isTrue();
        return imported;
    }

    /** 규칙이 쓰는 패키지 · 계층 판정. 인터페이스는 private 필드를 갖지 못해 중첩 클래스로 둔다 — 서비스가 직접 쓸 일은 없다. */
    final class Support {

        static final String DOMAINLAYER = "..domainlayer..";
        static final String CONTROLLER = "..domainlayer..adapter.in.*.controller..";
        static final String ENTRY_DTO = "..domainlayer..adapter.in.*.dto..";
        static final String PRESENTER = "..domainlayer..adapter.in.*.presenter..";
        static final String ADAPTER = "..domainlayer..adapter..";
        static final String ADAPTER_IN = "..domainlayer..adapter.in..";
        static final String ADAPTER_OUT = "..domainlayer..adapter.out..";
        static final String PERSISTENCE_ENTITY = "..domainlayer..adapter.out.persistence.entity..";
        static final String APPLICATION = "..domainlayer..application..";
        static final String PORT = "..domainlayer..application.port..";
        static final String PORT_IN = "..domainlayer..application.port.in..";
        static final String COMMAND = "..domainlayer..application.command..";
        static final String MODEL = "..domainlayer..application.model..";
        static final String APPLICATION_EXCEPTION = "..domainlayer..application.exception..";
        static final String ENTRY_EXCEPTION_HANDLER = "..domainlayer..adapter.in.*.exception..";
        static final String ENTRY_SUPPORT = "..domainlayer..adapter.in.support..";
        static final String COMMON_DTO = "com.commercefoundry.common.dto..";
        static final String COMMON_DTO_METADATA = "com.commercefoundry.common.dto.metadata..";
        static final String COMMON_RESPONSE = "com.commercefoundry.common.dto.Response";
        static final String FEIGN = "feign..";
        static final String OPENFEIGN = "org.springframework.cloud.openfeign..";
        static final String PORT_OUT = "..domainlayer..application.port.out..";
        static final String INFO = "..domainlayer..application.info..";
        static final String SERVICE = "..domainlayer..application.service..";
        static final String PROCESSOR = "..domainlayer..application.service.processor..";
        static final String MAPPER = "..domainlayer..application.mapper..";
        static final String DOMAIN = "..domainlayer..domain..";

        private Support() {
        }

        static boolean in(JavaClass type, String packagePattern) {
            return JavaClass.Predicates.resideInAPackage(packagePattern).test(type.getBaseComponentType());
        }

        /** {@code adapter/in/*}/controller 패키지의 클래스, 또는 domainlayer 의 {@code @Controller}(메타 포함 — {@code @RestController}). */
        static DescribedPredicate<JavaClass> controllers() {
            return DescribedPredicate.describe("컨트롤러(adapter/in/*/controller 또는 domainlayer 의 @Controller)",
                type -> in(type, CONTROLLER) || in(type, DOMAINLAYER) && type.isMetaAnnotatedWith(Controller.class));
        }

        /** Facade — {@code application/service} 바로 아래(processor 제외)의 {@code *Facade}. 중첩 · 익명 클래스는 바깥 클래스 이름으로 본다. */
        static boolean isFacade(JavaClass type) {
            return in(type, SERVICE) && !in(type, PROCESSOR) && topLevelSimpleName(type).endsWith("Facade");
        }

        static DescribedPredicate<JavaClass> facades() {
            return DescribedPredicate.describe("Facade(application/service 의 *Facade)", Support::isFacade);
        }

        /**
         * H1 — 컨트롤러가 쓰는 application 타입: UseCase({@code port/in}), 그 입력({@code command} · {@code model} — 정렬 enum 같은 {@code @RequestParam}),
         * 검증 메시지 상수({@code application/exception} 의 {@code *ValidationMessage}).
         */
        static boolean isControllerApplicationType(JavaClass type) {
            return in(type, PORT_IN) || in(type, COMMAND) || in(type, MODEL)
                || in(type, APPLICATION_EXCEPTION) && topLevelSimpleName(type).endsWith("ValidationMessage");
        }

        /** H12 — Feign 타입 또는 공통 응답 DTO({@code com.commercefoundry.common.dto..}). */
        static boolean isTransportType(JavaClass type) {
            return in(type, FEIGN) || in(type, OPENFEIGN) || in(type, COMMON_DTO);
        }

        /** H12 의 예외 — {@code common.dto.metadata} 는 어디서나, 응답 봉투 {@code Response} 는 UseCase · Facade 에서만. */
        static boolean allowedTransportType(JavaClass origin, JavaClass target) {
            return in(target, COMMON_DTO_METADATA)
                || target.getBaseComponentType().getName().equals(COMMON_RESPONSE) && (in(origin, PORT_IN) || isFacade(origin));
        }

        static boolean isProcessor(JavaClass type) {
            return in(type, PROCESSOR);
        }

        /** H2 의 문서화된 예외 셋 ({@code architecture-guide.md} §3 Port / Adapter). */
        static boolean documentedAdapterException(JavaClass origin, JavaClass target) {
            return in(origin, PORT_IN) && in(target, ENTRY_DTO)
                || isFacade(origin) && (in(target, PRESENTER) || in(target, ENTRY_DTO))
                || in(origin, MAPPER) && in(target, PERSISTENCE_ENTITY);
        }

        /** {@code adapter.in.web · admin · internal · webhook} 중 어느 진입인지. support · scheduler 는 진입이 아니다(support 는 H6 이 따로 본다). */
        static Optional<String> entryOf(JavaClass type) {
            for (String entry : new String[] {"web", "admin", "internal", "webhook"}) {
                if (in(type, "..domainlayer..adapter.in." + entry + "..")) {
                    return Optional.of(entry);
                }
            }
            return Optional.empty();
        }

        /** {@code domainlayer.<context>} 의 context. domainlayer 밖이면 비어 있다. */
        static Optional<String> contextOf(JavaClass type) {
            String[] segments = type.getBaseComponentType().getPackageName().split("\\.");
            for (int index = 0; index < segments.length - 1; index++) {
                if (segments[index].equals("domainlayer")) {
                    return Optional.of(segments[index + 1]);
                }
            }
            return Optional.empty();
        }

        static boolean crossContext(JavaClass origin, JavaClass target) {
            Optional<String> targetContext = contextOf(target);
            return contextOf(origin).flatMap(context -> targetContext.filter(other -> !other.equals(context))).isPresent();
        }

        /**
         * 다른 컨텍스트의 Processor(최상위 {@code *Processor} 클래스 — 중첩 타입 제외)는 Facade 에서만, domain model 은 Facade · Processor 에서만,
         * 예외({@code application/exception})는 자기 {@code adapter/in/*}/exception 의 {@code *ExceptionHandler}(그 Processor 를 부른 쪽이 번역)에서만 쓴다.
         */
        static boolean allowedAcrossContexts(JavaClass origin, JavaClass target) {
            return isTopLevelProcessor(target) && isFacade(origin)
                || in(target, DOMAIN) && (isFacade(origin) || isProcessor(origin))
                || in(target, APPLICATION_EXCEPTION) && in(origin, ENTRY_EXCEPTION_HANDLER) && topLevelSimpleName(origin).endsWith("ExceptionHandler");
        }

        private static boolean isTopLevelProcessor(JavaClass type) {
            JavaClass base = type.getBaseComponentType();
            return in(base, PROCESSOR) && base.getName().indexOf('$') < 0 && base.getSimpleName().endsWith("Processor");
        }

        /** 클래스가 직접 의존하는 타입 중 {@code forbidden} 인 것마다 위반을 낸다. 메시지에 의존 위치와 고치는 법을 싣는다. */
        static ArchCondition<JavaClass> notDependOn(String description, ForbiddenDependency forbidden, String fix) {
            return new ArchCondition<>(description) {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                    for (Dependency dependency : type.getDirectDependenciesFromSelf()) {
                        if (forbidden.test(dependency.getOriginClass(), dependency.getTargetClass())) {
                            events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription() + " — " + fix));
                        }
                    }
                }
            };
        }

        private static String topLevelSimpleName(JavaClass type) {
            String name = type.getName().substring(type.getPackageName().isEmpty() ? 0 : type.getPackageName().length() + 1);
            int nested = name.indexOf('$');
            return nested < 0 ? name : name.substring(0, nested);
        }
    }

    /** 의존 한 건(origin → target)이 규칙을 어기는지. */
    @FunctionalInterface
    interface ForbiddenDependency {

        boolean test(JavaClass origin, JavaClass target);
    }
}
