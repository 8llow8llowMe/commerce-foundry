---
name: hexagonal-guard
description: "Commerce Foundry 백엔드의 Controller·UseCase·Facade·Processor·Presenter와 Port·Adapter 계층 경계를 읽기 전용으로 점검한다. 아키텍처 리뷰·새 컨텍스트·계층 리팩토링에 사용한다."
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

## 빠른 탐지

```bash
# application의 adapter 의존 (문서화된 예외를 구분해 확인)
rg -n 'import com\.commercefoundry\.domainlayer\..*\.adapter\.' backend/service -g '*.java' -g '**/application/**'

# Processor의 Response 생성 후보
rg -n 'Response\.builder\(\)|Response\(' backend/service -g '*Processor.java'

# Controller의 직접 주입 후보
rg -n 'private final .*?(Facade|Processor|Port|Repository) ' backend/service -g '*Controller.java'
```

## What to Check

1. **계층 소유권**
   - Controller 는 UseCase 만 호출한다. 요청 바인딩·인증 주체·응답 래핑까지만
   - Facade 가 메인 오케스트레이터다
   - Processor 는 `Info` 또는 domain model 을 반환한다 (Response DTO 금지)
   - Presenter 가 유일한 `Info → Response` 매퍼다

2. **Port / Adapter 경계**
   - `application` 은 `adapter` 타입에 의존하지 않는다. 허용 예외는 셋뿐이다:
     ① `port/in` 반환 타입이 web/admin dto ② Facade 의 Presenter 주입 ③ `application/mapper` 의 Entity 참조
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
