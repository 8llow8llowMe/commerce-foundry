# Commerce Foundry Frontend Agent Guide

`frontend/` 작업의 Codex·Cursor 초기 지침이다. 공통 규칙은 [루트 AGENTS.md](../AGENTS.md), 상세 규칙은 `docs/`를 따른다.

## 구조

| 경로 | 역할 |
|------|------|
| `apps/storefront` | 고객 스토어프론트, 포트 3000 |
| `apps/admin` | 어드민 콘솔, 포트 3001, 중립 테마·noindex |
| `packages/api-client` | `@cf/api-client`: 봉투·ApiError·금액·멱등 키, `/server`: BFF·재발급·헬스체크 |
| `packages/ui` | 브랜드 무관 컴포넌트·중립 토큰 |
| `packages/theme-<brand>` | 브랜드 토큰·설정, 앱은 `@cf/brand` 별칭만 import |

## 먼저 읽을 문서

- [architecture.md](docs/architecture.md) — 패키지 경계·BFF·쿠키·i18n
- [api-client.md](docs/api-client.md) — 봉투·ApiError·멱등 키·금액
- [styling-theme.md](docs/styling-theme.md) — 토큰·브랜드 설정
- [conventions.md](docs/conventions.md) — 네이밍·import·테스트
- [local-run.md](docs/local-run.md) — Node·pnpm·환경변수·게이트웨이
- [deploy.md](docs/deploy.md) — standalone 이미지·배포 compose·빌드/런타임 env

## 핵심 규칙

- 브라우저는 게이트웨이를 직접 부르지 않는다. 클라이언트는 `apiFetch('/api/v1/...')`, 서버 컴포넌트는 `serverFetch`다.
- 토큰은 httpOnly 쿠키에만 둔다. JS·localStorage에서 읽거나 저장하지 않는다.
- 서버 금액을 `formatMoney`로 표시한다. 클라이언트에서 합계·할인을 계산하지 않는다.
- 색은 `bg-primary` 같은 토큰 클래스만 사용한다. 앱에 브랜드 이름을 하드코딩하지 않는다.
- 스토어프론트 화면은 `app/[locale]/` 아래, 링크는 `@/components/i18n/Link`, 문구는 사전·브랜드 `content`다.
- Next.js API를 사용하기 전에 앱의 `node_modules/next/dist/docs/`에서 해당 가이드를 확인한다.
  `next dev`가 만드는 `apps/*/AGENTS.md`는 커밋하지 않는다.
- 구현·검토 역할은 루트 `.agents/roles/fe-implementer.md`, `.agents/roles/fe-reviewer.md`를 필요할 때 읽는다.

## 명령

`frontend/`에서 실행한다. Node·pnpm 버전은 `package.json`의 `engines`와 `packageManager`를 따른다.

```bash
pnpm install
pnpm dev
pnpm lint
pnpm format:check
pnpm typecheck
pnpm test
pnpm build
```

완료 보고 전에 변경에 필요한 검사를 실제 실행한다. 미실행·실패는 그대로 보고한다.
문서·AI 설정만 바꾼 경우에는 관련 경로·형식·인코딩 검증을 실행한다. PowerShell에서는 명령을 한 줄씩 실행하고 종료 코드를 확인한다.
