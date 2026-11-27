---
name: backend-multi-agent
description: "Commerce Foundry의 큰 백엔드 작업을 Leader·Executor·계층·DB·보안·결제 검토 역할로 나눈다. 여러 경계가 함께 바뀌거나 팀 작업을 요청할 때 사용하며 작은 수정에는 사용하지 않는다."
---

# Backend Multi-Agent Playbook

## Codex · Cursor 실행 방식

- 역할 지침 원문은 저장소 루트 기준 `.agents/roles/<역할>.md`다. 네이티브 하위 에이전트로는
  Cursor `.cursor/agents/`와 Codex `.codex/agents/`에 11개 역할이 모두 생성돼 있다.
- Codex에서는 `spawn_agent`에 `agent_type`으로 `.codex/agents/`의 snake_case 이름을 지정한다
  (예: `be_executor`, `be_db_reviewer`). 도구 목록에 이름이 없으면 현재 세션에서 인식되지 않은 것이므로 새 세션에서 확인한다.
- 병렬은 `dev-orchestrator`의 `병렬 실행` 절(네 패턴 · 금지 조건 · 합치기)을 따른다. 한 턴에서 `spawn_agent`를 여러 번 연달아 호출한 뒤 `wait_agent`로 결과를 모은다.
  검토 fan-out 은 영역 리뷰어와 `reviewer`를 같은 턴에 띄운다. 쓰기 병렬은 작업자마다 별도 worktree 를 만들어 그 경로를 지시에 넣는다.
- 하위 에이전트를 사용할 수 있고 위임이 허용된 경우에만 역할 지침·범위·기대 출력을 전달해 위임한다.
  그 외에는 메인 세션이 동일한 역할을 순차 수행하고, 실제 수행 방식을 보고한다.
- 읽기 전용 역할은 소스·설정·문서를 편집하지 않는다. 실행 도구의 권한 제한을 적용할 수 있으면 함께 적용한다.
  검사 명령은 빌드 산출물을 만들 수 있지만, 파괴적인 셸 명령과 외부 변경은 허용되지 않는다.
- 쓰기 병렬의 조건(소유권 분리 · worktree 격리 · 금지 조건 없음)을 모두 만족하지 않으면 쓰기는 한 번에 한 역할만 수행한다.
  Codex 역할은 부모 세션 sandbox 를 상속하므로 읽기 전용은 역할 본문 지시로만 제한된다(Cursor 는 생성된 `readonly`).
- Codex 쓰기 병렬은 작업자 worktree 가 현재 sandbox 쓰기 범위 안일 때만 쓴다. 범위 밖이면 sandbox 를 넓히지 말고 순차로 진행한다.
- 범위 밖 이슈 등록·커밋·푸시·PR·머지는 실행하지 않는다. FE 작업은 `fe-implementer`와 `fe-reviewer`를 선택한다.

백엔드 작업이 구현과 검토를 역할로 나눌 만큼 클 때 사용한다.

## When to Use

- 새 백엔드 서비스 또는 주요 컨텍스트 추가 (예: payment-service 신설)
- DB / API / 서비스 간 호출 / docs 가 함께 바뀌는 작업
- 결제 승인·취소, 주문 상태, 재고 선점처럼 실수 비용이 큰 흐름 변경
- 사용자가 명시적으로 멀티 에이전트/팀 방식을 요청할 때

사용하지 않는다: 단일 파일 수정, 명확한 버그 1건, 문구·로그·Swagger 정리.

## Read First

1. [backend/docs/architecture-guide.md](../../../backend/docs/architecture-guide.md)
2. [backend/docs/coding-conventions.md](../../../backend/docs/coding-conventions.md)
3. [backend/docs/api-design-guide.md](../../../backend/docs/api-design-guide.md)
4. [backend/docs/done-checklist.md](../../../backend/docs/done-checklist.md)
5. 해당 시 [payment-guide.md](../../../backend/docs/payment-guide.md), [order-flow-guide.md](../../../backend/docs/order-flow-guide.md)

## Roles

| 역할 | 에이전트 | 권한 | 하는 일 |
|------|----------|------|---------|
| Leader | (메인 세션) | — | 범위·out-of-scope·공개 API·서비스 경계 고정, 검토 의견 수용/반려, 최종 통합 |
| Executor | `be-executor` | 쓰기 | Controller~Adapter 실구현, compile/test 통과 |
| Hexagonal Reviewer | `be-hexagonal-reviewer` | 읽기 전용 | 계층 흐름, Port/Adapter 경계, 고객/어드민 분리 |
| DB Reviewer | `be-db-reviewer` | 읽기 전용 | 엔티티, 인덱스, 쿼리 수단, N+1, 금액 컬럼, 락 |
| Security Reviewer | `be-security-reviewer` | 읽기 전용 | JWT, ADMIN 경로, 소유 확인, 비밀정보 (보안 변경 시) |
| Payment Reviewer | `be-payment-reviewer` | 읽기 전용 | 결제·주문·재고 정합성, 멱등, UNKNOWN (결제·주문 변경 시) |

## Role Set by Task Shape

| 작업 | Role Set |
|------|----------|
| 단일 서비스 기능 추가 | Leader + Executor + Hexagonal |
| 엔티티·쿼리·인덱스 변경 | Leader + Executor + DB + Hexagonal |
| 인증·인가·어드민 권한 변경 | Leader + Executor + Security + Hexagonal |
| 결제·주문 상태·재고 변경 | Leader + Executor + **Payment** + DB (+ Security: 웹훅·키 관련 시) |
| 신규 서비스 / 대형 리팩토링 | Leader + Executor + DB + Hexagonal (+ 필요 시 Security·Payment) |

## Procedure

1. **분류** — 위 표에서 작업 모양을 고른다.
2. **Leader 고정** — 범위, out-of-scope, 공개 API(고객/어드민/internal), 서비스 경계, 보존할 동작을 먼저 적는다.
3. **구현** — Executor 한 명만 쓴다. 같은 파일을 두 역할이 동시에 고치지 않는다.
   모듈 소유권이 겹치지 않는 구현이 둘 이상이면 `/dev-orchestrator` `병렬 실행` (c) 의 조건(워크트리 격리 · 금지 조건 없음)과 합치기 규칙을 지켜
   Executor 를 동시에 돌릴 수 있다. PR 조각이 나뉘면 (d) 파이프라인으로 앞 조각 검토와 다음 조각 구현을 겹친다.
4. **검토** — 해당 Reviewer 들과 최종 `reviewer` 는 읽기 전용이므로 위임이 허용되고 하위 에이전트를 사용할 수 있으면 **병렬로** 부르고, 그 외에는 메인 세션이 순차 검토한다. 각자 파일·행·근거가 있는 보고만 낸다.
5. **통합** — Leader 가 지적을 수용/반려한다. CRITICAL/HIGH는 Executor가 해결하고 다시 검증한다. MEDIUM/LOW는 영향·범위에 따른 처리 이유를 남긴다.
6. **검증** — Leader 가 직접 명령을 다시 돌린다. 하위 에이전트의 "통과했다" 를 완료 근거로 쓰지 않는다.

## Execution Rules

- 각 하위 에이전트에는 **역할, 읽을 문서, 허용된 변경 범위, 기대 출력**만 전달한다.
- 호출마다 `/dev-orchestrator` `모델 라우팅` 절의 작업 등급으로 모델 · 추론을 정해 넘긴다. 이 플레이북을 쓰는 작업은 대부분 핵심 등급이고
  (T3 구현은 추론 한 단계 위), Leader 가 설계를 고정하지 못하면 `architect`(설계 등급)를 먼저 부른다.
- 하위 에이전트는 다시 에이전트를 만들지 않는다. 커밋·푸시·이슈·PR 도 만들지 않는다.
- 최종 판단과 통합은 항상 메인 세션이 맡는다.

## Output Format

```text
BACKEND MULTI-AGENT PLAN
========================

Task:      ...
Issue:     #N
Role Set:  Leader + Executor + ...

Leader Decisions:
- scope / out-of-scope / API / boundaries

Execution Order:
1. Executor 구현
2. Reviewers 검토 (위임 가능 시 병렬, 그 외 순차)
3. 통합 패스
4. 검증

Review Decisions:
- accepted: ...
- rejected: ... (사유)

Verification:
- 실제로 돌린 명령과 결과

Remaining Risks:
- ...
```
