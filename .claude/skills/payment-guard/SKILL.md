---
name: payment-guard
description: commerce-foundry 결제 코드(payment-service, 결제와 맞물린 order-service)를 payment-guide 기준으로 점검한다. 금액 서버 검증, 중복 승인 방지(상태 전이 + Idempotency-Key), PG 호출과 트랜잭션 분리, UNKNOWN 처리, 웹훅 재조회·멱등, 부분취소 한도, USD 소수 변환, 키·로그 노출을 본다. 결제 승인·취소·환불·웹훅·PG adapter 변경이나 결제 관련 PR 직전이 트리거다.
---

# Payment Guard

결제 흐름에서 **돈과 기록이 갈라지는** 결함을 찾는다. 코드를 고치지 않고 보고한다.
깊은 검토가 필요하면 `be-payment-reviewer` 에이전트에 위임한다.

## Read First

1. [backend/docs/payment-guide.md](../../../backend/docs/payment-guide.md) — 전 절
2. [backend/docs/order-flow-guide.md](../../../backend/docs/order-flow-guide.md) §1~3 (결제와 맞물린 주문·재고)
3. [backend/docs/architecture-guide.md](../../../backend/docs/architecture-guide.md) §3 Facade 트랜잭션 예외

## 대상 확보

```bash
git diff --name-only origin/develop...HEAD -- backend/service/payment-service backend/service/order-service backend/core/shared-commerce
git status --short -- backend
```

diff 밖도 본다 — 승인 코드가 바뀌었으면 **취소·웹훅·재조회 스케줄러가 같은 상태 규칙을 쓰는지** 함께 확인한다.

## What to Check (위에서부터 심각도 순)

1. **금액 검증** — CRITICAL
   - 승인 전 `payment.amount`·`currency` 와 successUrl 로 돌아온 값을 비교하는가
   - 불일치 시 **PG 승인 요청을 보내지 않고** 거절하는가
   - 결제 금액의 출처가 order-service 의 서버 계산 금액인가 (프론트 값 ✕)

2. **중복 승인 방지** — CRITICAL
   - `READY → IN_PROGRESS` 전이가 낙관적 락 / 조건부 UPDATE 로 **한 번만** 성공하는가
   - 이미 `IN_PROGRESS`/`DONE` 이면 PG 를 다시 부르지 않는가
   - `Idempotency-Key` 가 **재시도해도 같은 값**인가 (요청마다 새 UUID 면 결함)

3. **트랜잭션과 PG 호출** — HIGH
   - PG 호출이 `@Transactional` 메서드 안에 있지 않은가 (Facade·Processor 둘 다 본다)
   - 승인 전 상태 기록이 **커밋된 뒤** PG 를 부르는가

4. **결과 미확정** — HIGH
   - 타임아웃·5xx·서킷 오픈을 `FAILED` 로 처리하지 않고 `UNKNOWN` 으로 남기는가
   - `UNKNOWN` 을 PG 조회로 확정하는 스케줄러/경로가 있는가
   - 고객에게 "실패" 대신 "확인 중" 으로 응답하는가

5. **주문 동기화** — HIGH
   - 결제 `DONE` 후 order-service 알림이 실패해도 재전송 플래그(`order_synced`)로 남는가
   - order-service 의 결제 완료 처리가 멱등인가 (이미 PAID 면 성공 응답)
   - 재고 선점 만료 후 결제 완료 시 자동 취소 경로가 있는가

6. **웹훅** — HIGH
   - 본문을 믿지 않고 PG 조회로 재확인하는가
   - 같은 이벤트 재수신에 멱등한가, 허용되지 않은 전이를 무시하는가
   - 무거운 처리 없이 빠르게 200 을 돌려주는가

7. **취소 · 환불** — HIGH
   - 취소 누계가 승인 금액을 넘지 않게 막는가 (부분취소)
   - 환불 금액 계산이 order-service 에 있고 payment-service 는 실행만 하는가
   - 실패 시 `REFUND_FAILED` 로 남기는가 (조용히 완료 ✕)

8. **통화** — HIGH
   - 금액이 `long` 최소 단위인가, `double`/`float` 가 없는가
   - USD 소수 변환이 `CurrencyCode.fractionDigits` 를 쓰고 adapter 에만 있는가

9. **키 · 로그** — CRITICAL (발견 시)
   - 시크릿 키가 코드·yml·테스트 픽스처에 평문으로 없는가 (`test_sk_`/`live_sk_` 문자열 검색)
   - prod 에서 test 키 / local 에서 live 키 기동을 막는 가드가 있는가
   - 로그에 결제키 전체·카드 정보·고객 개인정보·Authorization 헤더가 없는가

10. **테스트**
    - 성공·거절·타임아웃·5xx·중복 승인·금액 불일치·웹훅 재수신 경로가 테스트되는가

```bash
# 빠른 탐지
grep -rn "test_sk_\|live_sk_\|live_ck_" backend --include=*.java --include=*.yml --include=*.properties
grep -rn "double .*[Aa]mount\|float .*[Aa]mount\|Double .*[Aa]mount" backend/service --include=*.java
grep -rn -B3 "paymentGatewayPort\.\|PaymentGatewayPort" backend/service/payment-service/src/main/java --include=*.java | grep -n "@Transactional"
```

## Output Format

```text
PAYMENT GUARD REPORT
====================

Scope: [files / flows]

Findings:
1. [CRITICAL|HIGH|MEDIUM|LOW] [file:line]
   - 어떤 상황에서 돈과 기록이 갈라지는가 (구체적 시나리오)
   - expected rule (payment-guide §)
   - recommended fix

Verified Safe:
- [확인한 안전 경로]

Untested Paths:
- [테스트가 없는 위험 경로]
```
