---
name: dev-orchestrator
description: commerce-foundry 개발 작업을 SIMPLE·FEATURE·BUG·REFACTOR·ARCHITECTURE·LARGE_FEATURE 유형과 복잡도 T0–T3 로 분류한 뒤, 필요한 하위 에이전트(.claude/agents)만 골라 탐색 → 계획 → 구현 → 검증 → 검토 순서로 진행한다. 결제·재고·인증처럼 실수 비용이 큰 신호를 세어 검토 깊이를 정한다. /dev-orchestrator 요청과 비단순 개발 작업이 트리거다.
---

# Development Orchestrator

메인 세션이 작업 유형과 복잡도를 정한 뒤, 필요한 역할만 실행한다.
역할·모델·권한의 정본은 [docs/claude-agents.md](../../../docs/claude-agents.md) 다.

## 1. CLASSIFY

| 유형 | 대상 |
|------|------|
| `SIMPLE` | 위치와 계약이 분명한 DTO, 매핑, 단순 검증·설정, 작은 테스트, 문구 |
| `FEATURE` | 일반적인 단일 서비스 기능 |
| `BUG` | 원인 분석이 필요한 버그. 상태·트랜잭션·동시성이 얽히면 여기 |
| `REFACTOR` | 동작을 보존하면서 구조를 바꾸는 변경 |
| `ARCHITECTURE` | 서비스 경계·서비스 간 정합성·보안·메시징 설계 |
| `LARGE_FEATURE` | 여러 서비스(또는 BE+FE)에 걸쳐 발견 역할이 둘 이상 필요한 작업 |

## 2. COMPLEXITY

| 등급 | 의미 | 기본 깊이 |
|------|------|-----------|
| `T0` | 위치·계약·검증이 확정된 국소 변경 | 메인 세션이 직접. 탐색·설계·별도 검토 생략 가능 |
| `T1` | 한 모듈 안의 일반 구현·수정 | 구현 역할 + 대상 테스트 |
| `T2` | 여러 계층, 공개 API, 쿼리·인덱스, 경계 검토가 함께 있는 작업 | 구현 + 검토 역할 1~2 |
| `T3` | 실수 비용이 큰 작업 | 설계 고정 + 구현 + 해당 검토 역할 전부 |

`T3` 는 아래 신호 중 **2개 이상**이 작업 범위에 실제로 있을 때다. 하나뿐이면 `T2` 다.

- **결제 (승인·취소·환불·웹훅)**
- **재고 선점·만료처럼 시간과 동시성이 있는 상태**
- 주문·결제 상태 전이 변경
- 멱등성
- 인증·인가 · 어드민 권한
- 개인정보 (주소·전화번호)
- 서비스 간 정합성 (한쪽만 성공하는 경로)
- DB 스키마 변경 · 대량 데이터 이관
- 공개 API 하위 호환 (FE 계약 변경)
- Gateway·security-core 공통 변경
- 장애 원인 미상

## 3. EXPLORE

- `T0` 이거나 수정 위치가 이미 특정되면 생략한다.
- 위치·호출 흐름이 불명확할 때만 `explorer` 를 부른다.

## 4. PLAN

- `T0`·`T1` 은 메인 세션이 범위와 순서를 짧게 고정한다.
- `T2` 이상은 구현 전에 범위, 보존할 동작, 검증 방법을 고정한다. 새 컨텍스트면 `/backend-feature-bootstrap`.
- `ARCHITECTURE` 와 `T3` 는 `architect` 의 설계가 저장소 증거와 맞을 때만 구현으로 넘긴다.
- `LARGE_FEATURE` 는 백엔드에 `/backend-multi-agent` 를 적용하고, FE 영향은 이슈로 분리해 `seonghoho` 에게 넘긴다.

## 5. IMPLEMENT

| 유형 | 구현 |
|------|------|
| `SIMPLE` | 메인 세션이 직접 |
| `FEATURE` · `BUG`(원인 확정 후) · `REFACTOR` | `be-executor` 한 명 |
| `ARCHITECTURE` | 구현 요청이 있을 때만, 확정된 계획을 `be-executor` 에게 |

공유 파일·공개 계약·DB 스키마·도메인 객체를 고치는 쓰기 역할은 **한 번에 하나만** 실행한다.

## 6. VERIFY

대상 범위의 컴파일과 관련 테스트를 **메인 세션이 직접** 돌린다. 하위 에이전트의 "통과했다" 를 완료 근거로 쓰지 않는다.

```bash
cd backend
./gradlew :service:<svc>:cleanTest :service:<svc>:test --no-build-cache
./gradlew check   # 여러 모듈이면
```

## 7. REVIEW

| 범위 | 검토 역할 |
|------|-----------|
| 계층·Port/Adapter | `be-hexagonal-reviewer` |
| 엔티티·쿼리·인덱스 | `be-db-reviewer` |
| 인증·인가·어드민·키 | `be-security-reviewer` |
| 결제·주문·재고 | `be-payment-reviewer` |
| 최종 diff 전반 | `reviewer` |

- `T0` 은 대상 테스트로 끝낸다. `T1`·`T2` 는 해당 검토 1~2개, `T3` 는 해당 검토 전부 + `reviewer`.
- 검토 역할은 읽기 전용이라 **한 메시지에서 병렬로** 부른다.
- CRITICAL/HIGH 지적만 구현 역할이 고친 뒤 다시 검증한다.

## 완료 조건

1. 요청한 동작이 구현되었다.
2. 영향 범위가 컴파일되고 관련 테스트가 실제로 돌아 통과했다.
3. 필요한 수준의 검토가 끝났고 CRITICAL/HIGH 지적이 해결되었다.
4. 불필요한 변경과 겹치는 동시 편집이 없다.
5. 보고에 `TYPE`, `COMPLEXITY`(T3 면 해당 신호), 실제 사용한 역할, 실행한 검증, 남은 위험이 있다.
