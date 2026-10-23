# Backend Docs

## 목적

- `commerce-foundry/backend` 작업의 기준 문서 모음이다.
- `backend/CLAUDE.md` 는 엔트리 역할만 하고, 규칙의 정본은 이 디렉터리다.

## 문서 구성

| 문서 | 내용 |
|------|------|
| `architecture-guide.md` | MSA 모듈 구성, Hexagonal 패키지, 계층 책임, 보안 기준, 멀티 스토어 기준 |
| `coding-conventions.md` | 줄 길이, 네이밍, Swagger, 식별자 String, 예외·검증 코드, 엔티티·금액·쿼리, Feign·서킷 |
| `api-design-guide.md` | 경로 체계(고객/어드민/internal/webhook), 응답·오류 봉투, 금액 표현, 페이지네이션, 멱등성 |
| `payment-guide.md` | 토스페이먼츠 통합 결제 — 승인 흐름, 중복 방지, UNKNOWN, 웹훅, 환불, USD/PayPal |
| `order-flow-guide.md` | 주문·재고·배송·클레임 상태 전이와 동시성 |
| `security-guide.md` | JWT 토큰 사양(RS256 · claim · refresh 불투명 토큰), 인증·인가 오류 코드, Resource Server 정책, 키 생성·교체, 서비스 간 인증(서비스 토큰 · 허용 호출자) |
| `bootstrap-conventions.md` | Application 클래스, Global Config 템플릿 |
| `local-run-guide.md` | 로컬 빌드·테스트, PostgreSQL·Redis compose, 서비스 실행·게이트웨이 경유 헬스 체크·Swagger UI, 문제 해결 |
| `secret-management.md` | Vault 시크릿 관리 — 키 이름 규칙(`../.env.example`), 환경별 출처, 키 추가 절차, 유출 대응 |
| `service-inventory.md` | 서비스 책임·포트·호출 관계·결정 대기 항목 |
| `jenkins-cicd-guide.md` | Jenkins 멀티브랜치 배포 — Actions 와의 역할 분담, 잡 · agent · lock · Vault, 변경 경로 · 라벨 게이트, FORCE_DEPLOY · 되돌리기, 실패 대응 |
| `done-checklist.md` | 기능 단위 완료 기준 |

## 권장 읽기 순서

1. `../CLAUDE.md`
2. `architecture-guide.md`
3. `coding-conventions.md`
4. `api-design-guide.md`
5. 결제·주문 작업이면 `payment-guide.md`, `order-flow-guide.md` / 인증·인가 작업이면 `security-guide.md`
6. `service-inventory.md`
7. `done-checklist.md`
8. 처음 받았으면 `local-run-guide.md`

## 작업 원칙

- 새 기능 구현 전에 API 경로, 계층 책임, 보안 방식부터 정한다.
- 코드 변경과 문서 변경은 같이 움직인다. 구현 중 새 규칙이 생기면 엔트리보다 이 디렉터리를 먼저 고친다.
- 문서는 추상 지침만 적지 않고, 구현이 생기면 실제 클래스 이름을 본보기로 적는다.

## 스킬

Claude Code 에서 `/스킬명` 으로 호출한다.

| 스킬 | 용도 |
|------|------|
| `/backend-feature-bootstrap` | 새 서비스·컨텍스트 착수 — 책임·API·패키지 스켈레톤·검증 계획 |
| `/backend-api-check` | REST 경로, Swagger, Presenter 흐름, 고객/어드민 분리 점검 |
| `/hexagonal-guard` | Hexagonal 계층 경계 누수 점검 |
| `/payment-guard` | 결제 흐름 점검 (금액 검증, 멱등, UNKNOWN, 웹훅, 키 관리) |
| `/order-flow-guard` | 주문·재고·배송·클레임 상태 전이와 동시성 점검 |
| `/backend-multi-agent` | 큰 작업을 역할별(구현/계층/DB/보안/결제 검토)로 나눠 진행 |
