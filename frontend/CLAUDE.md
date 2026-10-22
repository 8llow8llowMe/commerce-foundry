# Commerce Foundry Frontend Claude Guide

## 목적

- `frontend/` 에서 작업할 때 먼저 읽는 엔트리다. 규칙 정본은 `frontend/docs/` 다. 이 문서는 지도다.
- 저장소 공통 규칙(공개 저장소·커밋 prefix·Git 워크플로)은 루트 `CLAUDE.md` 를 따른다.

## 구조

| 경로 | 역할 |
|------|------|
| `apps/storefront` | 고객 스토어프론트 (:3000) |
| `apps/admin` | 어드민 콘솔 (:3001, 중립 테마, noindex) |
| `packages/api-client` | 응답 봉투·ApiError·금액·멱등 키 (`@cf/api-client`), BFF 프록시·재발급·헬스체크 (`@cf/api-client/server`) |
| `packages/ui` | 브랜드 무관 컴포넌트 + 중립 토큰 (`@cf/ui`, `@cf/ui/tokens.css`) |
| `packages/theme-<brand>` | 브랜드 토큰 값 + brand config. 앱은 별칭 `@cf/brand` 로만 import 한다 |

## 명령 (`frontend/` 에서)

```bash
pnpm install
pnpm dev            # 두 앱 동시 기동
pnpm lint && pnpm format:check && pnpm typecheck && pnpm test && pnpm build
```

## 문서

| 문서 | 내용 |
|------|------|
| [docs/architecture.md](docs/architecture.md) | 모노레포·패키지 경계·BFF 흐름·쿠키 원칙 |
| [docs/api-client.md](docs/api-client.md) | 봉투·ApiError kind 처리·멱등 키·금액 표시 |
| [docs/styling-theme.md](docs/styling-theme.md) | 토큰 두 층·임의 색 금지·새 브랜드 추가 |
| [docs/local-run.md](docs/local-run.md) | Node·pnpm·env·게이트웨이와 함께 띄우기 |
| [docs/deploy.md](docs/deploy.md) | standalone 이미지·배포 compose·빌드 시점 vs 런타임 env |
| [docs/conventions.md](docs/conventions.md) | 파일·네이밍·import·테스트 규칙 |

## 핵심 규칙

- 브라우저에서 게이트웨이를 직접 부르지 않는다. 클라이언트는 `apiFetch('/api/v1/...')`, 서버 컴포넌트는 `serverFetch`.
- 토큰은 httpOnly 쿠키에만 있다. JS 에서 토큰을 읽거나 localStorage 에 두지 않는다.
- 금액은 서버 값을 `formatMoney` 로 표시만 한다. 클라이언트에서 합계·할인을 계산하지 않는다.
- 색은 토큰 클래스(`bg-primary` 등)로만 쓴다. 앱 코드에 브랜드 이름을 하드코딩하지 않는다.
- 스토어프론트 화면은 `app/[locale]/` 아래다. 링크는 `@/components/i18n/Link`, 문구는 사전 · 브랜드 `content` 에 둔다(`docs/architecture.md` §5).
- Next.js 16 은 학습 데이터와 API·규칙이 다를 수 있다. Next API 를 쓰기 전에 앱 디렉터리의
  `node_modules/next/dist/docs/` 해당 가이드를 확인한다(`next dev` 가 만드는 `apps/*/AGENTS.md` 는 커밋하지 않는다).
- 완료 보고 전에 위 검사 명령을 실제로 돌린다.

## 에이전트

`fe-implementer`(구현), `fe-reviewer`(읽기 전용 검토). 정본은 루트 `docs/claude-agents.md`.
