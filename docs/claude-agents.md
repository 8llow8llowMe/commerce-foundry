# Claude Code 역할별 에이전트 운영 가이드

## 목적

작업의 난이도와 위험도에 맞춰 하위 에이전트(subagent)와 모델을 고른다. 단순 작업에 고비용 추론을 반복하지 않으면서,
결제·주문·보안 작업에는 충분한 검토 깊이를 확보하는 것이 목적이다.

정의 파일은 `.claude/agents/*.md` 이므로 이 저장소를 여는 다른 PC·세션에도 똑같이 적용된다.
개인 인증·권한 설정(`.claude/settings.local.json`)은 저장소에 넣지 않는다.

## 운영 결론

**모든 대화에서 에이전트를 자동으로 병렬 실행하지 않는다.** 역할 파일은 항상 발견 가능하게 두고,
`dev-orchestrator` 가 개발 작업을 분류해 필요한 역할만 호출한다.

- 명시 호출: `/dev-orchestrator 작업 내용`
- 직접 요청: `be-payment-reviewer 로 이 브랜치 결제 흐름 검토해줘`

## 역할과 모델

### 공용

| 역할 | 모델 | 권한 | 사용 시점 |
|------|------|------|-----------|
| `explorer` | Sonnet | 읽기 전용 | 파일 위치, 호출 흐름, 서비스 간 의존, 영향 범위 |
| `architect` | Fable | 읽기 전용 | 서비스 경계, 서비스 간 정합성, 보안 구조, 메시징 도입 설계 |
| `reviewer` | Opus | 읽기 전용 | 최종 diff 의 정확성·회귀·보안·테스트 검토 |

### 백엔드

| 역할 | 모델 | 권한 | 사용 시점 |
|------|------|------|-----------|
| `be-executor` | Opus | 쓰기 | Hexagonal 계층·Port/Adapter 실구현, compile/test 통과까지 |
| `be-hexagonal-reviewer` | Opus | 읽기 전용 | 계층 흐름, Port/Adapter 경계, 고객/어드민 진입 분리 |
| `be-db-reviewer` | Opus | 읽기 전용 | 엔티티·인덱스·쿼리·락·금액 컬럼 (N+1·초과 판매 우선) |
| `be-security-reviewer` | Opus | 읽기 전용 | JWT, ADMIN 경로, 소유 확인(IDOR), 서비스 간 인증(internal API · internal Feign 클라이언트), 비밀정보·개인정보 |
| `be-payment-reviewer` | Opus | 읽기 전용 | 결제·주문·재고 정합성 — 실패·중복·지연 주입 표 |

Leader 는 파일이 없다. **메인 세션이 맡는다** (`/backend-multi-agent`).

### 프론트엔드

| 역할 | 모델 | 권한 | 사용 시점 |
|------|------|------|-----------|
| `fe-implementer` | Opus | 쓰기 | 앱 화면·route handler·packages 실구현, format·lint·typecheck·test·build 통과까지 |
| `fe-reviewer` | Opus | 읽기 전용 | BFF·쿠키 경계, ApiError 처리, 금액·멱등 키, 테마 토큰 규칙 검토 |

새 프론트 역할은 `fe-*` 접두어로 추가하고 이 표에 같은 형식으로 적는다.

## 모델 배정 기준

- **설계·아키텍처 → Fable**, **어려운 구현·검토·정합성 분석 → Opus**, **탐색·단순 반복 → Sonnet**.
- 세션 모델과 하위 에이전트 모델은 따로다. 메인 세션의 모델을 하위 에이전트에 상속시키지 않고 역할 파일의 `model` 을 쓴다.
- 메인 세션은 계획·범위 결정·최종 완료 판단을 직접 한다. 이 판단을 하위 에이전트에 위임하지 않는다.

## 권한 규칙

- 검토·탐색·설계 역할은 frontmatter `tools: Read, Grep, Glob, Bash` 로 **읽기 전용을 강제**한다. `Write`/`Edit` 를 넣지 않는다.
  `Bash` 는 `git diff`·검사 실행용이고, 본문에서 편집 금지를 명시한다.
- 쓰기 역할(`be-executor`)은 `tools` 를 생략해 전체 도구를 상속한다.
- 모든 역할은 **커밋·푸시·이슈·PR 을 만들지 않고, 하위 에이전트를 다시 만들지 않는다.** 그 일은 메인 세션의 몫이다.

## 병렬화 규칙

- 서로 독립적인 **읽기 전용** 조사·검토만 한 메시지 안에서 병렬로 부른다 (예: hexagonal + db + payment 리뷰어 동시).
- 같은 파일을 고치는 쓰기 역할은 한 번에 하나만 실행한다.
- 하위 에이전트의 "통과했다" 를 완료 근거로 쓰지 않는다. 메인 세션이 검증 명령을 다시 돌린다.

## 주의

사용자 전역(`~/.claude/agents/`)에 같은 이름의 다른 프로젝트용 에이전트가 있을 수 있다. 이 저장소의 프로젝트 스코프 정의가
우선한다. 역할이 이 저장소 규칙(`com.commercefoundry`, `adapter/in/admin`, `payment-guide.md`)을 모르는 것처럼 굴거나
**hondigagae 경로·여행 도메인 용어가 등장하면 잘못된 정의를 읽고 있는 것이다.**
