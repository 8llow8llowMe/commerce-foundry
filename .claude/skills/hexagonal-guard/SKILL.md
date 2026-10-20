---
name: hexagonal-guard
description: commerce-foundry 백엔드의 Hexagonal 계층(Controller/UseCase/Facade/Processor/Presenter, Port/Adapter) 경계 누수를 점검한다. 새 컨텍스트를 추가했거나, 계층이 흐릿하거나, PG·Feign 응답 타입이 application 으로 새어나갈 조짐이 보이면 사용한다.
---

# Hexagonal Guard

백엔드의 계층 누수와 헥사고날 경계 파손을 막는다. 코드를 고치지 않고 보고한다.

## When to Use

- 아키텍처 리뷰나 헥사고날 리팩토링 요청이 있을 때
- 새 컨텍스트 또는 서비스를 추가할 때
- Processor / Adapter / Presenter / Mapper 경계가 흐릿해졌을 때
- Port 반환 타입이나 adapter import 가 상위 계층으로 새기 시작했을 때

## Read First

1. [backend/docs/architecture-guide.md](../../../backend/docs/architecture-guide.md) §2~5
2. [backend/docs/coding-conventions.md](../../../backend/docs/coding-conventions.md) §4~5

## 기계로 보는 것 / 사람이 보는 것

계층 의존 12종(H1~H12 — Controller 는 UseCase 만, application → adapter 금지와 예외 3종, Processor → adapter 금지, domain 순수,
out-port 의 Info 금지, 진입 간 타입 공유 금지, in → out adapter 금지, 컨텍스트 간 허용 목록, Presenter 는 변환만,
스케줄러 · 웹훅 포함 진입 전체가 UseCase 경유, Facade → out-port 금지, Feign · `common.dto` 전송 타입의 application · domain 누수 금지)은
**빌드가 본다** — 서비스 `<Svc>ArchitectureTest` 가 `HexagonalArchitectureRules` 를 implements 한다 (정본 `architecture-guide.md` §3 "기계로 보는 것").
`./gradlew :service:<svc>:test --tests '*ArchitectureTest'` 가 통과하면 그 항목은 다시 보지 않는다. 실패 메시지에 고치는 법이 있다.

이 스킬은 **규칙이 보지 못하는 것**에 집중한다 — 아래 What to Check 의 4 · 5 와 2 의 이름 · 반환 의미, 3 의 필드 누출 · isAdmin 분기,
그리고 §2 패키지 구조 · 이름 접미사(`*Facade` · `*Processor` · `*ExceptionHandler`)를 비켜 간 클래스, `global` 패키지, H12 패턴 밖의 외부 전송 타입(PG SDK 등).

## 빠른 탐지

```bash
# 계층 의존 규칙 (H1~H12) — 먼저 돌린다
cd backend && ./gradlew :service:<svc>:test --tests '*ArchitectureTest'

# application 이 adapter 를 import 하는 곳 (문서화된 예외 3개를 걸러서 본다)
grep -rn "import com.commercefoundry.domainlayer.*\.adapter\." backend/service/*/src/main/java --include=*.java | grep "/application/"

# Processor 가 Response DTO 를 만드는 곳
grep -rln "Response.builder()\|Response(" backend/service/*/src/main/java --include=*Processor.java

# Controller 가 Facade/Processor/Port 를 직접 주입하는 곳
grep -rn "private final .*\(Facade\|Processor\|Port\|Repository\) " backend/service/*/src/main/java --include=*Controller.java
```

## What to Check

1. **계층 소유권**
   - Controller 는 UseCase 만 호출한다. 요청 바인딩·인증 주체·응답 래핑까지만
   - Facade 가 메인 오케스트레이터다
   - Processor 는 `Info` 또는 domain model 을 반환한다 (Response DTO 금지)
   - Presenter 가 유일한 `Info → Response` 매퍼다

2. **Port / Adapter 경계**
   - `application` 은 `adapter` 타입에 의존하지 않는다. 허용 예외는 셋뿐이다:
     ① `port/in` 의 진입 DTO(web · admin · internal) ② Facade 의 Presenter 주입 ③ `application/mapper` 의 Entity 참조
   - **PG(토스·PayPal) 응답 DTO, Feign 응답 래퍼, 택배사 응답이 application 에 나타나지 않는다**
   - out-port 이름이 전송 기술이 아니라 책임이다 (`PaymentGatewayPort` ○, `TossClientPort` ✕)
   - out-port 반환이 `QueryResult` 또는 domain model 이다 (`Info` ✕)

3. **고객 / 어드민 진입 분리**
   - `in/web` 과 `in/admin` 이 DTO 를 공유하지 않는다
   - Processor·Port 는 공유해도 된다 — 어드민 전용 로직이 Processor 에 `if (isAdmin)` 으로 섞이지 않았는지 본다

4. **쓰기 흐름**
   - `domain → entity → repository.save → entity → domain` 을 유지한다
   - 상태 변경이 도메인 메서드로 일어난다 (엔티티 setter·서비스의 `status = X` 직접 대입 ✕)
   - ID 생성은 Processor 나 상위 오케스트레이션에서 한다

5. **트랜잭션 경계**
   - 읽기 `@Transactional(readOnly = true)`, 쓰기 `@Transactional`
   - **Feign·PG·소셜 로그인 호출이 들어 있는 Facade 에 `@Transactional` 이 붙어 있으면 HIGH** —
     원격 호출 성공 후 롤백되면 외부와 우리 DB 가 갈라진다. 좁힌 경우 이유 주석이 있는지 본다

## Output Format

```text
HEXAGONAL GUARD REPORT
======================

Scope: [context or service]

Boundary Issues:
1. [CRITICAL|HIGH|MEDIUM|LOW] [file:line]
   - current leak
   - expected boundary (doc §)
   - recommended refactor

Healthy Patterns:
- [유지할 가치가 있는 패턴]

Next Fix Order:
1. ...
```
