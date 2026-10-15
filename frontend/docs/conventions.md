# Frontend Conventions

## 1. 파일·이름

- 컴포넌트 파일은 `PascalCase.tsx`, 그 외는 `kebab-case.ts`.
- 앱 내부 import 는 `@/` 별칭(`src/`), 패키지는 `@cf/*` 이름으로 import 한다. 패키지 사이에 상대 경로로 넘나들지 않는다.
- 패키지 공개 API 는 `src/index.ts`(서버 전용은 `src/server/index.ts`)로만 내보낸다.

## 2. 코드

- TypeScript strict + `noUncheckedIndexedAccess`. `any` 대신 `unknown` 과 좁히기.
- TS 6 은 `types` 기본값이 `[]` 다. Node 전역을 쓰는 패키지는 tsconfig 에 `"types": ["node"]` 를 둔다.
- 사용자 문구는 한국어. 서버 `resultMessage` 를 우선한다.
- 서버 전용 모듈(`@cf/api-client/server`, `next/headers`)을 클라이언트 컴포넌트에서 import 하지 않는다.
- 환경변수는 앱의 `src/lib/env.ts` 에서만 읽는다.

## 3. 테스트

- Vitest, 대상 파일 옆 `*.test.ts(x)`. 실행은 `frontend/` 루트에서 `pnpm test`.
- 네트워크는 `fetch` 를 인자로 주입해 `vi.fn<typeof fetch>()` 로 대신한다.
- 컴포넌트는 `react-dom/server` 의 `renderToStaticMarkup` 문자열로 확인한다(DOM 환경 없음).
- 새 분기·새 오류 kind 에는 테스트를 같이 더한다. CI 는 `packages/api-client` · `packages/ui` 테스트가 0 건이면 실패한다.

## 4. 포맷·lint

- Prettier: 작은따옴표, 세미콜론, trailing comma, 100자. md 는 포맷하지 않는다.
- ESLint: `eslint-config-next`(core-web-vitals + typescript) + 임의 색 값 금지. 설정 파일도 default export 를 변수에 담아 내보낸다.
