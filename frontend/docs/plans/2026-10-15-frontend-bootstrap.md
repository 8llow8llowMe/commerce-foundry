# 프론트엔드 초기 구성 Implementation Plan (#23)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `frontend/` 에 pnpm·Turborepo 모노레포(스토어프론트·어드민 앱 + api-client·ui·theme 패키지)를 세우고, BFF 프록시·봉투 파싱·금액 유틸·테마 토큰·frontend-ci·규칙 문서까지 갖춘다.

**Architecture:** 브라우저는 같은 origin 의 Next route handler(`/api/[...path]`)만 부르고, 핸들러가 httpOnly 쿠키의 토큰을 `Authorization` 으로 바꿔 게이트웨이(:8000)에 중계한다. 401 이면 서버에서 한 번 재발급·재시도한다. 핵심 로직은 프레임워크 무관한 `@cf/api-client`(Web `Request`/`Response` 만 사용)에 두고 앱은 얇게 연결만 한다. 브랜드는 pnpm 별칭 `@cf/brand` 가 가리키는 테마 패키지의 CSS 변수로 바뀐다.

**Tech Stack:** Node 24, pnpm 10.30.3, Turborepo 2.11.8, Next.js 16.4.1 (App Router, Turbopack), React 19.3.1, TypeScript 6.0.3, Tailwind CSS 4.3.3, ESLint 9.39.5 + eslint-config-next 16.4.1 + Prettier 3.9.10, Vitest 5.0.4

**Spec:** `frontend/docs/specs/2026-10-15-frontend-bootstrap-design.md`

## Global Constraints

- 모든 작업 경로는 저장소의 `frontend/` 아래다. 명령은 별도 표기가 없으면 `frontend/` 에서 실행한다.
- Node 24 (`.nvmrc` = `24`). 로컬 기본이 20 이면 `nvm use 24` (또는 시스템 Node 24) 후 진행한다.
- 패키지 매니저 `pnpm@10.30.3` 고정. 의존성 버전은 `^` 없이 정확히 고정하고, 공유 버전은 `pnpm-workspace.yaml` 의 `catalog:` 로 한 번만 적는다.
- **TypeScript 는 6.0.x 다. 7.x 를 쓰지 않는다** — typescript-eslint 8.x 의 peer 범위가 `<6.1.0` 이다.
- **ESLint 는 9.x 다.** eslint-config-next 가 쓰는 플러그인 호환을 위해 10.x 를 쓰지 않는다.
- TS 6 은 `types` 기본값이 `[]` 다. Node 전역(`process`, `Buffer`)을 쓰는 tsconfig 는 `"types": ["node"]` 를 명시한다.
- 패키지 스코프는 `@cf/`. 패키지는 빌드하지 않고 TS 소스를 export 한다(앱은 `transpilePackages`).
- 공개 저장소다. 사업자등록번호·대표자 등 고객사 실정보, 토큰·키 실값을 어디에도 쓰지 않는다. `.env.example` 에는 키 이름만.
- 파일은 UTF-8(no BOM), LF, 2칸 들여쓰기(`.editorconfig`).
- `git add -A` / `git add .` / `git stash` 금지. 경로를 하나씩 스테이징한다.
- 커밋 제목 형식: `[FE] chore: ... (#23)`. CI·Claude 설정은 `[INFRA]`, 문서만이면 `[DOCS]`. 본문 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- 사용자 문구는 한국어. 서버 `resultMessage` 가 있으면 그것을 우선한다.

## Review Focus

- `GATEWAY_URL` 끝에 `/` 가 붙어 있어도(`http://localhost:8000/`) 게이트웨이로 `//api/v1/...` 같은 이중 슬래시 경로를 보내지 않는다 — 게이트웨이가 `//` 를 400 으로 거절한다. → Task 3 `url.test.ts`
- 쿠키 값이 깨진 퍼센트 인코딩(`cf_at=%E0%A4%A`)이면 500 이 아니라 쿠키가 없는 것으로 다룬다. → Task 3 `cookies.test.ts`
- 재발급은 성공했는데 재시도 요청이 네트워크 오류로 실패하면, 503 응답에도 **새 쿠키를 심는다**. refresh 토큰이 회전돼 옛 값이 무효라 쿠키를 버리면 강제 로그아웃된다. → Task 3 `proxy.test.ts`
- 성공 응답이 `204 No Content`(본문 없음)여도 `invalidResponse` 가 아니라 `null` 로 성공 처리한다. → Task 2 `unwrap.test.ts`
- 화면 이탈로 `AbortController` 가 요청을 취소하면 `ApiError('network')` 로 바꾸지 않고 `AbortError` 를 그대로 던진다 — 오류 토스트가 뜨면 안 된다. → Task 2 `api-fetch.test.ts`

---

## File Structure

```text
frontend/
  package.json · pnpm-workspace.yaml · pnpm-lock.yaml · turbo.json · .nvmrc
  tsconfig.base.json · eslint.config.mjs · .prettierrc.json · .prettierignore
  vitest.config.ts · .gitignore · .env.example
  CLAUDE.md
  docs/ architecture.md · api-client.md · styling-theme.md · local-run.md · conventions.md
  packages/
    api-client/
      package.json · tsconfig.json
      src/index.ts             공개 진입점 (브라우저·서버 공용)
      src/envelope.ts          봉투·Slice·Page 타입, isApiEnvelope, errorEnvelope
      src/api-error.ts         ApiError, ApiErrorKind, classifyFailure, messagesFor
      src/unwrap.ts            Response → dataBody | throw ApiError
      src/api-fetch.ts         브라우저용 같은 origin fetch 래퍼
      src/money.ts             Money 타입, formatMoney
      src/idempotency.ts       createIdempotencyKey
      src/server/index.ts      서버 전용 진입점 (route handler·RSC 에서만 import)
      src/server/url.ts        joinUrl
      src/server/cookies.ts    AuthCookieConfig, readCookie, serializeCookie
      src/server/reissue.ts    reissueTokens (동시 요청 dedupe)
      src/server/proxy.ts      createProxyHandler
      src/server/gateway-request.ts  RSC 용 게이트웨이 직접 호출
      src/server/health.ts     checkGateway, healthResponse
      (각 파일 옆 *.test.ts)
    ui/
      package.json · tsconfig.json
      src/index.ts · src/tokens.css · src/brand.ts · src/cx.ts
      src/Button.tsx · src/FieldError.tsx · src/TextField.tsx (+ *.test.tsx)
    theme-mokoyuri/
      package.json · tsconfig.json · theme.css · src/brand.ts
  apps/
    storefront/  (admin/ 도 같은 구조)
      package.json · tsconfig.json · next.config.ts · postcss.config.mjs
      src/app/layout.tsx · page.tsx · globals.css
      src/app/api/[...path]/route.ts · src/app/api/health/route.ts
      src/lib/env.ts · auth-cookies.ts · server-api.ts
.github/workflows/frontend-ci.yml
.github/scripts/frontend-test-count.mjs
.claude/agents/fe-implementer.md · fe-reviewer.md
CLAUDE.md · docs/claude-agents.md (수정)
```

---

### Task 1: 모노레포 골격과 도구

**Files:**
- Create: `frontend/package.json`, `frontend/pnpm-workspace.yaml`, `frontend/turbo.json`, `frontend/.nvmrc`, `frontend/tsconfig.base.json`, `frontend/eslint.config.mjs`, `frontend/.prettierrc.json`, `frontend/.prettierignore`, `frontend/vitest.config.ts`, `frontend/.gitignore`, `frontend/.env.example`
- Generated: `frontend/pnpm-lock.yaml`

**Interfaces:**
- Produces: 루트 스크립트 `dev` · `build` · `typecheck`(turbo) · `lint`(eslint .) · `format` · `format:check` · `test` · `test:ci`(vitest JSON → `test-results/vitest.json`). catalog 키: `next`, `react`, `react-dom`, `typescript`, `@types/node`, `@types/react`, `@types/react-dom`, `vitest`, `tailwindcss`, `@tailwindcss/postcss`.

- [ ] **Step 1: Node·pnpm 확인**

Run: `node -v && pnpm -v`
Expected: `v24.x` 와 `10.30.3`. Node 가 20 이면 `nvm use 24` (없으면 `nvm install 24`).

- [ ] **Step 2: `frontend/.nvmrc`**

```text
24
```

- [ ] **Step 3: `frontend/pnpm-workspace.yaml`**

```yaml
packages:
  - apps/*
  - packages/*

# 공유 의존성 버전은 여기 한 곳에만 적는다. 패키지는 "catalog:" 로 참조한다.
catalog:
  next: 16.4.1
  react: 19.3.1
  react-dom: 19.3.1
  typescript: 6.0.3
  '@types/node': 24.19.2
  '@types/react': 19.3.0
  '@types/react-dom': 19.3.0
  vitest: 5.0.4
  tailwindcss: 4.3.3
  '@tailwindcss/postcss': 4.3.3
```

- [ ] **Step 4: `frontend/package.json`**

```json
{
  "name": "commerce-foundry-frontend",
  "private": true,
  "packageManager": "pnpm@10.30.3",
  "engines": {
    "node": ">=24"
  },
  "scripts": {
    "dev": "turbo run dev",
    "build": "turbo run build",
    "typecheck": "turbo run typecheck",
    "lint": "eslint .",
    "format": "prettier --write .",
    "format:check": "prettier --check .",
    "test": "vitest run",
    "test:ci": "vitest run --reporter=default --reporter=json --outputFile.json=test-results/vitest.json"
  },
  "devDependencies": {
    "@types/node": "catalog:",
    "eslint": "9.39.5",
    "eslint-config-next": "16.4.1",
    "eslint-config-prettier": "10.1.8",
    "prettier": "3.9.10",
    "turbo": "2.11.8",
    "typescript": "catalog:",
    "vitest": "catalog:"
  }
}
```

- [ ] **Step 5: `frontend/turbo.json`**

lint·test 는 루트에서 한 번에 돌리므로 turbo 태스크는 build·typecheck·dev 만 둔다.

```json
{
  "$schema": "https://turborepo.com/schema.json",
  "tasks": {
    "build": {
      "dependsOn": ["^build"],
      "outputs": [".next/**", "!.next/cache/**"],
      "env": ["GATEWAY_URL", "NEXT_PUBLIC_SITE_URL"]
    },
    "typecheck": {
      "dependsOn": ["^typecheck"],
      "outputs": []
    },
    "dev": {
      "cache": false,
      "persistent": true,
      "passThroughEnv": ["GATEWAY_URL", "NEXT_PUBLIC_SITE_URL"]
    }
  }
}
```

- [ ] **Step 6: `frontend/tsconfig.base.json`**

```json
{
  "compilerOptions": {
    "target": "ES2023",
    "lib": ["DOM", "DOM.Iterable", "ES2023"],
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "jsx": "react-jsx",
    "strict": true,
    "noUncheckedIndexedAccess": true,
    "noImplicitOverride": true,
    "isolatedModules": true,
    "verbatimModuleSyntax": true,
    "resolveJsonModule": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "noEmit": true,
    "types": []
  }
}
```

- [ ] **Step 7: `frontend/eslint.config.mjs`**

```js
import { defineConfig, globalIgnores } from 'eslint/config';
import nextVitals from 'eslint-config-next/core-web-vitals';
import nextTs from 'eslint-config-next/typescript';
import prettier from 'eslint-config-prettier/flat';

// Tailwind 임의 색 값(bg-[#fff], text-[rgb(...)] 등)을 막는다. 색은 테마 토큰 클래스로만 쓴다.
const ARBITRARY_COLOR = '/\\b(bg|text|border|ring|outline|fill|stroke|from|via|to|decoration|shadow)-\\[(#|rgb|hsl|oklch)/';

export default defineConfig([
  ...nextVitals,
  ...nextTs,
  prettier,
  {
    settings: { next: { rootDir: ['apps/*/'] } },
  },
  {
    files: ['**/*.{ts,tsx}'],
    rules: {
      'no-restricted-syntax': [
        'error',
        {
          selector: `Literal[value=${ARBITRARY_COLOR}]`,
          message: '임의 색 값 대신 테마 토큰 클래스(bg-primary 등)를 쓴다. frontend/docs/styling-theme.md',
        },
        {
          selector: `TemplateElement[value.raw=${ARBITRARY_COLOR}]`,
          message: '임의 색 값 대신 테마 토큰 클래스(bg-primary 등)를 쓴다. frontend/docs/styling-theme.md',
        },
      ],
    },
  },
  globalIgnores([
    '**/node_modules/**',
    '**/.next/**',
    '**/.turbo/**',
    '**/next-env.d.ts',
    'test-results/**',
    'coverage/**',
  ]),
]);
```

- [ ] **Step 8: Prettier 설정**

`frontend/.prettierrc.json`:

```json
{
  "singleQuote": true,
  "semi": true,
  "trailingComma": "all",
  "printWidth": 100
}
```

`frontend/.prettierignore` (문서 표가 한글 폭 때문에 재정렬되는 것을 막으려고 md 는 제외한다):

```text
node_modules
.next
.turbo
test-results
coverage
pnpm-lock.yaml
*.md
```

- [ ] **Step 9: `frontend/vitest.config.ts`**

```ts
import { defineConfig } from 'vitest/config';

// 테스트는 루트에서 한 번에 돈다. 패키지별 설정을 두지 않는다.
export default defineConfig({
  oxc: { jsx: { runtime: 'automatic' } },
  test: {
    include: ['{apps,packages}/*/src/**/*.test.{ts,tsx}'],
    environment: 'node',
  },
});
```

- [ ] **Step 10: `frontend/.gitignore`**

루트 `.gitignore` 가 `frontend/**/node_modules/`, `frontend/**/.next/`, `.env*` 를 이미 무시한다. 나머지만 둔다.

```text
.turbo/
next-env.d.ts
test-results/
coverage/
*.tsbuildinfo
```

- [ ] **Step 11: `frontend/.env.example`**

```text
# 프론트엔드 환경변수 — 키 이름만 둔다. 실값은 앱 디렉터리의 .env.local 또는 배포 환경에 넣는다.
# 사업자 정보 실값은 Vault 에 있다 (backend/docs/secret-management.md).

# 서버 전용 — BFF · Server Component 가 부를 게이트웨이 주소 (기본값 http://localhost:8000)
GATEWAY_URL=

# 공개 — 절대 URL 이 필요한 곳(SEO 등)
NEXT_PUBLIC_SITE_URL=

# 서버 전용 — 푸터 사업자 정보 표시용 (#24 이후 사용)
BUSINESS_NAME=
BUSINESS_REPRESENTATIVE=
BUSINESS_REGISTRATION_NUMBER=
BUSINESS_MAIL_ORDER_NUMBER=
BUSINESS_ADDRESS=
BUSINESS_CONTACT=
```

- [ ] **Step 12: 설치**

Run: `pnpm install`
Expected: `pnpm-lock.yaml` 생성, 오류 없음. `Ignored build scripts:` 경고가 나오면 목록만 기록해 두고 넘어간다(빌드 스크립트가 필요한 패키지는 없다. Task 5 의 `pnpm build` 가 실패하면 그때 `pnpm-workspace.yaml` 의 `onlyBuiltDependencies` 에 해당 이름을 더한다).

- [ ] **Step 13: 도구 동작 확인**

Run: `pnpm lint && pnpm format:check`
Expected: 둘 다 오류 없이 종료. `format:check` 가 실패하면 `pnpm format` 후 다시 실행.

- [ ] **Step 14: Commit**

```bash
git add frontend/package.json frontend/pnpm-workspace.yaml frontend/pnpm-lock.yaml frontend/turbo.json \
  frontend/.nvmrc frontend/tsconfig.base.json frontend/eslint.config.mjs frontend/.prettierrc.json \
  frontend/.prettierignore frontend/vitest.config.ts frontend/.gitignore frontend/.env.example
git commit -m "[FE] chore: pnpm·Turborepo 모노레포 골격과 lint·format·test 도구를 구성한다 (#23)"
```

---

### Task 2: `@cf/api-client` — 봉투 파싱·오류 모델·금액·멱등 키

**Files:**
- Create: `frontend/packages/api-client/package.json`, `tsconfig.json`
- Create: `src/envelope.ts`, `src/api-error.ts`, `src/unwrap.ts`, `src/api-fetch.ts`, `src/money.ts`, `src/idempotency.ts`, `src/index.ts`
- Test: `src/api-error.test.ts`, `src/unwrap.test.ts`, `src/api-fetch.test.ts`, `src/money.test.ts`

**Interfaces:**
- Produces (import `@cf/api-client`):
  - `type FieldErrorItem = { code: string; field: string; message: string }`
  - `type DataHeader = { success: boolean; resultCode: string | null; resultMessage: string | null; fieldErrors: FieldErrorItem[] | null }`
  - `type ApiEnvelope<T> = { dataHeader: DataHeader; dataBody: T | null }`
  - `type SliceResponse<T> = { contents: T[]; hasNext: boolean }`
  - `type PageResponse<T> = { contents: T[]; page: number; size: number; totalElements: number; totalPages: number }`
  - `isApiEnvelope(value: unknown): value is ApiEnvelope<unknown>`
  - `errorEnvelope(resultCode: string, resultMessage: string): ApiEnvelope<null>`
  - `type ApiErrorKind = 'network' | 'invalidResponse' | 'validation' | 'unauthorized' | 'forbidden' | 'notFound' | 'conflict' | 'unknownOutcome' | 'unavailable' | 'server'`
  - `class ApiError extends Error { kind; status: number | null; code: string | null; fieldErrors: FieldErrorItem[] }` — `new ApiError(kind, status, code, message, fieldErrors?)`
  - `classifyFailure(status: number, header: DataHeader): ApiErrorKind`
  - `messagesFor(error: ApiError, field: string): string[]`
  - `unwrap<T>(response: Response): Promise<T>`
  - `apiFetch<T>(path: string, init?: ApiFetchInit, fetchImpl?: typeof fetch): Promise<T>`, `type ApiFetchInit = Omit<RequestInit, 'body'> & { json?: unknown; idempotencyKey?: string }`
  - `type Currency = 'KRW' | 'USD'`, `type Money = { amount: number; currency: Currency }`, `formatMoney(money: Money): string`
  - `createIdempotencyKey(): string`

- [ ] **Step 1: 패키지 파일**

`frontend/packages/api-client/package.json`:

```json
{
  "name": "@cf/api-client",
  "version": "0.0.0",
  "private": true,
  "type": "module",
  "exports": {
    ".": "./src/index.ts",
    "./server": "./src/server/index.ts"
  },
  "scripts": {
    "typecheck": "tsc --noEmit"
  },
  "devDependencies": {
    "@types/node": "catalog:",
    "typescript": "catalog:",
    "vitest": "catalog:"
  }
}
```

`frontend/packages/api-client/tsconfig.json`:

```json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": {
    "types": ["node"]
  },
  "include": ["src"]
}
```

Run: `pnpm install` (워크스페이스에 패키지 등록)

- [ ] **Step 2: `src/envelope.ts`**

```ts
// 백엔드 응답 봉투 계약. 정본은 backend/docs/api-design-guide.md §2 · §4 다.

export type FieldErrorItem = {
  code: string;
  field: string;
  message: string;
};

export type DataHeader = {
  success: boolean;
  resultCode: string | null;
  resultMessage: string | null;
  fieldErrors: FieldErrorItem[] | null;
};

export type ApiEnvelope<T> = {
  dataHeader: DataHeader;
  dataBody: T | null;
};

/** 스토어프론트 목록(무한 스크롤) */
export type SliceResponse<T> = {
  contents: T[];
  hasNext: boolean;
};

/** 어드민 목록(페이지 번호). page 는 0 부터 센다. */
export type PageResponse<T> = {
  contents: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export function isApiEnvelope(value: unknown): value is ApiEnvelope<unknown> {
  if (typeof value !== 'object' || value === null) return false;
  const header = (value as { dataHeader?: unknown }).dataHeader;
  return (
    typeof header === 'object' &&
    header !== null &&
    typeof (header as { success?: unknown }).success === 'boolean'
  );
}

export function errorEnvelope(resultCode: string, resultMessage: string): ApiEnvelope<null> {
  return {
    dataHeader: { success: false, resultCode, resultMessage, fieldErrors: null },
    dataBody: null,
  };
}
```

- [ ] **Step 3: 실패하는 테스트 — `src/api-error.test.ts`**

```ts
import { describe, expect, it } from 'vitest';
import { ApiError, classifyFailure, messagesFor } from './api-error';
import type { DataHeader } from './envelope';

const header = (overrides: Partial<DataHeader> = {}): DataHeader => ({
  success: false,
  resultCode: 'X_001',
  resultMessage: '메시지',
  fieldErrors: null,
  ...overrides,
});

describe('classifyFailure', () => {
  it.each([
    [401, 'unauthorized'],
    [403, 'forbidden'],
    [404, 'notFound'],
    [409, 'conflict'],
    [502, 'unknownOutcome'],
    [504, 'unknownOutcome'],
    [503, 'unavailable'],
    [500, 'server'],
    [400, 'server'],
  ] as const)('status %i → %s', (status, kind) => {
    expect(classifyFailure(status, header())).toBe(kind);
  });

  it('fieldErrors 가 있으면 상태와 무관하게 validation 이다', () => {
    const fieldErrors = [{ code: 'M_1', field: 'phone', message: '형식 오류' }];
    expect(classifyFailure(400, header({ fieldErrors }))).toBe('validation');
    expect(classifyFailure(409, header({ fieldErrors }))).toBe('validation');
  });

  it('빈 fieldErrors 배열은 validation 이 아니다', () => {
    expect(classifyFailure(400, header({ fieldErrors: [] }))).toBe('server');
  });
});

describe('ApiError', () => {
  it('서버 메시지가 없으면 kind 기본 문구를 쓴다', () => {
    expect(new ApiError('unavailable', 503, 'GATEWAY_002', null).message).toBe(
      '서비스가 잠시 응답하지 않습니다. 잠시 후 다시 시도해 주세요.',
    );
  });

  it('서버 메시지를 우선한다', () => {
    expect(new ApiError('notFound', 404, 'ORDER_001', '존재하지 않는 주문입니다.').message).toBe(
      '존재하지 않는 주문입니다.',
    );
  });
});

describe('messagesFor', () => {
  it('해당 필드의 메시지만 모은다', () => {
    const error = new ApiError('validation', 400, 'M_1', '입력 오류', [
      { code: 'M_1', field: 'phone', message: '형식 오류' },
      { code: 'M_2', field: 'email', message: '이메일 오류' },
      { code: 'M_3', field: 'phone', message: '필수' },
    ]);
    expect(messagesFor(error, 'phone')).toEqual(['형식 오류', '필수']);
    expect(messagesFor(error, 'name')).toEqual([]);
  });
});
```

Run: `pnpm vitest run packages/api-client/src/api-error.test.ts`
Expected: FAIL — `Failed to resolve import "./api-error"`

- [ ] **Step 4: `src/api-error.ts`**

```ts
import type { DataHeader, FieldErrorItem } from './envelope';

export type ApiErrorKind =
  | 'network'
  | 'invalidResponse'
  | 'validation'
  | 'unauthorized'
  | 'forbidden'
  | 'notFound'
  | 'conflict'
  | 'unknownOutcome'
  | 'unavailable'
  | 'server';

const DEFAULT_MESSAGES: Record<ApiErrorKind, string> = {
  network: '네트워크 연결을 확인해 주세요.',
  invalidResponse: '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
  validation: '입력값을 확인해 주세요.',
  unauthorized: '로그인이 필요합니다.',
  forbidden: '접근 권한이 없습니다.',
  notFound: '요청한 정보를 찾을 수 없습니다.',
  conflict: '요청을 처리하고 있습니다. 잠시 후 결과를 확인해 주세요.',
  unknownOutcome: '처리 결과를 확인하지 못했습니다. 잠시 후 결과를 확인해 주세요.',
  unavailable: '서비스가 잠시 응답하지 않습니다. 잠시 후 다시 시도해 주세요.',
  server: '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
};

export class ApiError extends Error {
  override readonly name = 'ApiError';
  readonly kind: ApiErrorKind;
  readonly status: number | null;
  readonly code: string | null;
  readonly fieldErrors: FieldErrorItem[];

  constructor(
    kind: ApiErrorKind,
    status: number | null,
    code: string | null,
    message: string | null,
    fieldErrors: FieldErrorItem[] = [],
  ) {
    super(message ?? DEFAULT_MESSAGES[kind]);
    this.kind = kind;
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
  }
}

/** 실패 응답의 kind 를 정한다. 판정 순서는 frontend/docs/api-client.md 표와 같다. */
export function classifyFailure(status: number, header: DataHeader): ApiErrorKind {
  if (header.fieldErrors && header.fieldErrors.length > 0) return 'validation';
  switch (status) {
    case 401:
      return 'unauthorized';
    case 403:
      return 'forbidden';
    case 404:
      return 'notFound';
    case 409:
      return 'conflict';
    case 502:
    case 504:
      return 'unknownOutcome';
    case 503:
      return 'unavailable';
    default:
      return 'server';
  }
}

export function messagesFor(error: ApiError, field: string): string[] {
  return error.fieldErrors.filter((item) => item.field === field).map((item) => item.message);
}
```

Run: `pnpm vitest run packages/api-client/src/api-error.test.ts`
Expected: PASS

- [ ] **Step 5: 실패하는 테스트 — `src/unwrap.test.ts`**

```ts
import { describe, expect, it } from 'vitest';
import { ApiError } from './api-error';
import { unwrap } from './unwrap';

const envelope = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

describe('unwrap', () => {
  it('성공이면 dataBody 를 돌려준다', async () => {
    const res = envelope(200, {
      dataHeader: { success: true, resultCode: null, resultMessage: null, fieldErrors: null },
      dataBody: { id: 1 },
    });
    await expect(unwrap(res)).resolves.toEqual({ id: 1 });
  });

  it('성공 + dataBody null 은 null 이다 (선택적 하위 리소스 부재)', async () => {
    const res = envelope(200, {
      dataHeader: { success: true, resultCode: null, resultMessage: null, fieldErrors: null },
      dataBody: null,
    });
    await expect(unwrap(res)).resolves.toBeNull();
  });

  it('204 No Content 는 null 로 성공 처리한다', async () => {
    await expect(unwrap(new Response(null, { status: 204 }))).resolves.toBeNull();
  });

  it('실패 봉투는 ApiError 로 던진다', async () => {
    const res = envelope(404, {
      dataHeader: {
        success: false,
        resultCode: 'ORDER_001',
        resultMessage: '존재하지 않는 주문입니다.',
        fieldErrors: null,
      },
      dataBody: null,
    });
    const error = await unwrap(res).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      kind: 'notFound',
      status: 404,
      code: 'ORDER_001',
      message: '존재하지 않는 주문입니다.',
    });
  });

  it('fieldErrors 를 그대로 싣는다', async () => {
    const fieldErrors = [{ code: 'MEMBER_103', field: 'phone', message: '형식 오류' }];
    const res = envelope(400, {
      dataHeader: { success: false, resultCode: 'MEMBER_103', resultMessage: '형식 오류', fieldErrors },
      dataBody: null,
    });
    await expect(unwrap(res)).rejects.toMatchObject({ kind: 'validation', fieldErrors });
  });

  it('봉투가 아닌 JSON 은 invalidResponse 다', async () => {
    await expect(unwrap(envelope(403, { message: 'nope' }))).rejects.toMatchObject({
      kind: 'invalidResponse',
      status: 403,
    });
  });

  it('JSON 이 아닌 본문은 invalidResponse 다', async () => {
    await expect(unwrap(new Response('<html>', { status: 500 }))).rejects.toMatchObject({
      kind: 'invalidResponse',
      status: 500,
    });
  });

  it('success=true 인데 HTTP 오류 상태면 실패로 본다', async () => {
    const res = envelope(500, {
      dataHeader: { success: true, resultCode: null, resultMessage: null, fieldErrors: null },
      dataBody: null,
    });
    await expect(unwrap(res)).rejects.toMatchObject({ kind: 'server', status: 500 });
  });
});
```

Run: `pnpm vitest run packages/api-client/src/unwrap.test.ts`
Expected: FAIL — `Failed to resolve import "./unwrap"`

- [ ] **Step 6: `src/unwrap.ts`**

```ts
import { ApiError, classifyFailure } from './api-error';
import { isApiEnvelope } from './envelope';

/** 응답 봉투를 풀어 dataBody 를 돌려주고, 실패면 ApiError 를 던진다. */
export async function unwrap<T>(response: Response): Promise<T> {
  if (response.status === 204) return null as T;

  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    throw new ApiError('invalidResponse', response.status, null, null);
  }
  if (!isApiEnvelope(payload)) {
    throw new ApiError('invalidResponse', response.status, null, null);
  }

  const { dataHeader, dataBody } = payload;
  if (dataHeader.success && response.ok) return dataBody as T;

  throw new ApiError(
    classifyFailure(response.status, dataHeader),
    response.status,
    dataHeader.resultCode,
    dataHeader.resultMessage,
    dataHeader.fieldErrors ?? [],
  );
}
```

Run: `pnpm vitest run packages/api-client/src/unwrap.test.ts`
Expected: PASS

- [ ] **Step 7: 실패하는 테스트 — `src/api-fetch.test.ts`**

```ts
import { describe, expect, it, vi } from 'vitest';
import { apiFetch } from './api-fetch';

const ok = (body: unknown) =>
  new Response(
    JSON.stringify({
      dataHeader: { success: true, resultCode: null, resultMessage: null, fieldErrors: null },
      dataBody: body,
    }),
    { status: 200, headers: { 'content-type': 'application/json' } },
  );

describe('apiFetch', () => {
  it('같은 origin 으로 JSON 요청을 보내고 dataBody 를 돌려준다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(ok({ id: 7 }));

    const result = await apiFetch<{ id: number }>(
      '/api/v1/orders',
      { method: 'POST', json: { qty: 1 }, idempotencyKey: 'key-1' },
      fetchImpl,
    );

    expect(result).toEqual({ id: 7 });
    const [path, init] = fetchImpl.mock.calls[0]!;
    expect(path).toBe('/api/v1/orders');
    expect(init?.method).toBe('POST');
    expect(init?.credentials).toBe('same-origin');
    expect(init?.body).toBe('{"qty":1}');
    const headers = new Headers(init?.headers);
    expect(headers.get('content-type')).toBe('application/json');
    expect(headers.get('accept')).toBe('application/json');
    expect(headers.get('idempotency-key')).toBe('key-1');
  });

  it('본문이 없으면 Content-Type 을 붙이지 않는다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(ok([]));
    await apiFetch('/api/v1/products', {}, fetchImpl);
    const headers = new Headers(fetchImpl.mock.calls[0]![1]?.headers);
    expect(headers.has('content-type')).toBe(false);
    expect(fetchImpl.mock.calls[0]![1]?.body).toBeUndefined();
  });

  it('fetch 자체가 실패하면 network ApiError 다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('Failed to fetch'));
    await expect(apiFetch('/api/v1/products', {}, fetchImpl)).rejects.toMatchObject({
      name: 'ApiError',
      kind: 'network',
    });
  });

  it('취소(AbortError)는 ApiError 로 바꾸지 않고 그대로 던진다', async () => {
    const abort = new DOMException('The operation was aborted.', 'AbortError');
    const fetchImpl = vi.fn<typeof fetch>().mockRejectedValue(abort);
    await expect(apiFetch('/api/v1/products', {}, fetchImpl)).rejects.toBe(abort);
  });
});
```

Run: `pnpm vitest run packages/api-client/src/api-fetch.test.ts`
Expected: FAIL — `Failed to resolve import "./api-fetch"`

- [ ] **Step 8: `src/api-fetch.ts`**

```ts
import { ApiError } from './api-error';
import { unwrap } from './unwrap';

export type ApiFetchInit = Omit<RequestInit, 'body'> & {
  /** JSON 으로 직렬화할 본문 */
  json?: unknown;
  /** 쓰기 요청 멱등 키. 같은 사용자 의도의 재시도에는 같은 값을 쓴다. */
  idempotencyKey?: string;
};

/**
 * 브라우저에서 같은 origin 의 BFF(/api/v1/**)를 부른다.
 * 토큰은 httpOnly 쿠키라 여기서 다루지 않는다. 재발급도 BFF 가 한다.
 */
export async function apiFetch<T>(
  path: string,
  init: ApiFetchInit = {},
  fetchImpl: typeof fetch = fetch,
): Promise<T> {
  const { json, idempotencyKey, headers, ...rest } = init;
  const merged = new Headers(headers);
  merged.set('accept', 'application/json');
  if (json !== undefined) merged.set('content-type', 'application/json');
  if (idempotencyKey) merged.set('idempotency-key', idempotencyKey);

  let response: Response;
  try {
    response = await fetchImpl(path, {
      ...rest,
      headers: merged,
      credentials: 'same-origin',
      body: json === undefined ? undefined : JSON.stringify(json),
    });
  } catch (cause) {
    // 화면 이탈 등으로 취소된 요청은 오류 안내 대상이 아니다.
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause;
    throw new ApiError('network', null, null, null);
  }
  return unwrap<T>(response);
}
```

Run: `pnpm vitest run packages/api-client/src/api-fetch.test.ts`
Expected: PASS

- [ ] **Step 9: 실패하는 테스트 — `src/money.test.ts`**

```ts
import { describe, expect, it } from 'vitest';
import { formatMoney } from './money';

describe('formatMoney', () => {
  it('KRW 는 원 단위 정수를 "129,000원" 으로 표시한다', () => {
    expect(formatMoney({ amount: 129000, currency: 'KRW' })).toBe('129,000원');
  });

  it('USD 는 센트 정수를 달러로 표시한다', () => {
    expect(formatMoney({ amount: 8950, currency: 'USD' })).toBe('$89.50');
    expect(formatMoney({ amount: 5, currency: 'USD' })).toBe('$0.05');
  });

  it('0 과 음수(환불 등)를 표시한다', () => {
    expect(formatMoney({ amount: 0, currency: 'KRW' })).toBe('0원');
    expect(formatMoney({ amount: -3000, currency: 'KRW' })).toBe('-3,000원');
    expect(formatMoney({ amount: -8950, currency: 'USD' })).toBe('-$89.50');
  });

  it('정수가 아닌 amount 는 계약 위반으로 던진다', () => {
    expect(() => formatMoney({ amount: 89.5, currency: 'USD' })).toThrow(/최소 화폐 단위 정수/);
    expect(() => formatMoney({ amount: Number.NaN, currency: 'KRW' })).toThrow();
  });
});
```

Run: `pnpm vitest run packages/api-client/src/money.test.ts`
Expected: FAIL — `Failed to resolve import "./money"`

- [ ] **Step 10: `src/money.ts`**

```ts
// 금액 계약: amount 는 최소 화폐 단위 정수(KRW 원, USD 센트). backend/docs/api-design-guide.md §2-3
// 프론트는 표시만 한다. 합계·할인 계산은 서버 값을 쓴다.

export type Currency = 'KRW' | 'USD';

export type Money = {
  amount: number;
  currency: Currency;
};

function fractionDigits(currency: Currency): number {
  return (
    new Intl.NumberFormat('en-US', { style: 'currency', currency }).resolvedOptions()
      .maximumFractionDigits ?? 0
  );
}

export function formatMoney({ amount, currency }: Money): string {
  if (!Number.isSafeInteger(amount)) {
    if (process.env.NODE_ENV !== 'production') {
      throw new Error(`amount 는 최소 화폐 단위 정수여야 한다: ${amount} ${currency}`);
    }
  }
  const major = amount / 10 ** fractionDigits(currency);

  if (currency === 'KRW') {
    // 국내 쇼핑몰 관례: ₩129,000 이 아니라 129,000원
    return `${new Intl.NumberFormat('ko-KR').format(major)}원`;
  }
  return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(major);
}
```

Run: `pnpm vitest run packages/api-client/src/money.test.ts`
Expected: PASS

- [ ] **Step 11: `src/idempotency.ts` 와 `src/index.ts`**

`src/idempotency.ts`:

```ts
/**
 * 쓰기 요청 멱등 키. "사용자 의도 한 번"(주문하기 버튼 한 번)에 하나를 만들고,
 * 같은 의도의 재시도에는 같은 키를 다시 보낸다. backend/docs/api-design-guide.md §6
 */
export function createIdempotencyKey(): string {
  return crypto.randomUUID();
}
```

`src/index.ts`:

```ts
export { ApiError, classifyFailure, messagesFor, type ApiErrorKind } from './api-error';
export { apiFetch, type ApiFetchInit } from './api-fetch';
export {
  errorEnvelope,
  isApiEnvelope,
  type ApiEnvelope,
  type DataHeader,
  type FieldErrorItem,
  type PageResponse,
  type SliceResponse,
} from './envelope';
export { createIdempotencyKey } from './idempotency';
export { formatMoney, type Currency, type Money } from './money';
export { unwrap } from './unwrap';
```

- [ ] **Step 12: 전체 확인**

Run: `pnpm vitest run packages/api-client && pnpm --filter @cf/api-client typecheck && pnpm lint`
Expected: 모두 PASS / 오류 없음

- [ ] **Step 13: Commit**

```bash
git add frontend/packages/api-client/package.json frontend/packages/api-client/tsconfig.json \
  frontend/packages/api-client/src/envelope.ts frontend/packages/api-client/src/api-error.ts \
  frontend/packages/api-client/src/api-error.test.ts frontend/packages/api-client/src/unwrap.ts \
  frontend/packages/api-client/src/unwrap.test.ts frontend/packages/api-client/src/api-fetch.ts \
  frontend/packages/api-client/src/api-fetch.test.ts frontend/packages/api-client/src/money.ts \
  frontend/packages/api-client/src/money.test.ts frontend/packages/api-client/src/idempotency.ts \
  frontend/packages/api-client/src/index.ts frontend/pnpm-lock.yaml
git commit -m "[FE] feat: 응답 봉투 파싱·ApiError·금액 표시·멱등 키 유틸을 추가한다 (#23)"
```

---

### Task 3: `@cf/api-client/server` — BFF 프록시·재발급·헬스체크

**Files:**
- Create: `frontend/packages/api-client/src/server/url.ts`, `cookies.ts`, `reissue.ts`, `proxy.ts`, `gateway-request.ts`, `health.ts`, `index.ts`
- Test: `src/server/url.test.ts`, `cookies.test.ts`, `proxy.test.ts`, `gateway-request.test.ts`, `health.test.ts`

**Interfaces:**
- Consumes: `ApiError`, `unwrap`, `isApiEnvelope`, `errorEnvelope` (Task 2)
- Produces (import `@cf/api-client/server`):
  - `joinUrl(base: string, pathAndQuery: string): string`
  - `type AuthCookieConfig = { accessName: string; refreshName: string; accessMaxAgeSec: number; refreshMaxAgeSec: number; secure: boolean }`
  - `readCookie(cookieHeader: string | null, name: string): string | null`
  - `serializeCookie(name: string, value: string, maxAgeSec: number, secure: boolean): string`
  - `type TokenPair = { accessToken: string; refreshToken: string }`, `REISSUE_PATH = '/api/v1/auth/token/reissue'`
  - `reissueTokens(gatewayUrl: string, refreshToken: string, fetchImpl: typeof fetch): Promise<TokenPair | null>`
  - `type ProxyOptions = { gatewayUrl: string; cookies: AuthCookieConfig; fetch?: typeof fetch }`
  - `createProxyHandler(options: ProxyOptions): (request: Request) => Promise<Response>`
  - `gatewayRequest<T>(path: string, options: GatewayRequestOptions): Promise<T>`, `type GatewayRequestOptions = { gatewayUrl: string; accessToken?: string | null; init?: RequestInit; fetch?: typeof fetch }`
  - `type HealthStatus = 'UP' | 'DOWN'`, `checkGateway(gatewayUrl: string, fetchImpl?: typeof fetch, timeoutMs?: number): Promise<HealthStatus>`, `healthResponse(gatewayUrl: string, fetchImpl?: typeof fetch): Promise<Response>`
- BFF 자체 오류 코드: `BFF_001`(404, `/api/v1/**` 밖 경로), `BFF_002`(503, 게이트웨이 연결 실패)

- [ ] **Step 1: 실패하는 테스트 — `src/server/url.test.ts`**

```ts
import { describe, expect, it } from 'vitest';
import { joinUrl } from './url';

describe('joinUrl', () => {
  it('base 끝 슬래시를 하나도 남기지 않고 붙인다', () => {
    expect(joinUrl('http://localhost:8000', '/api/v1/a?x=1')).toBe('http://localhost:8000/api/v1/a?x=1');
    expect(joinUrl('http://localhost:8000/', '/api/v1/a')).toBe('http://localhost:8000/api/v1/a');
    expect(joinUrl('http://localhost:8000//', '/api/v1/a')).toBe('http://localhost:8000/api/v1/a');
  });

  it('base 의 경로 접두사를 보존한다', () => {
    expect(joinUrl('https://gw.example.com/edge/', '/api/v1/a')).toBe(
      'https://gw.example.com/edge/api/v1/a',
    );
  });
});
```

Run: `pnpm vitest run packages/api-client/src/server/url.test.ts` → FAIL (모듈 없음)

- [ ] **Step 2: `src/server/url.ts`**

```ts
/** base 끝 슬래시를 지우고 경로를 붙인다. 게이트웨이는 `//` 경로를 400 으로 거절한다. */
export function joinUrl(base: string, pathAndQuery: string): string {
  return base.replace(/\/+$/, '') + pathAndQuery;
}
```

Run: 같은 명령 → PASS

- [ ] **Step 3: 실패하는 테스트 — `src/server/cookies.test.ts`**

```ts
import { describe, expect, it } from 'vitest';
import { readCookie, serializeCookie } from './cookies';

describe('readCookie', () => {
  it('이름이 정확히 같은 쿠키 값을 디코딩해 읽는다', () => {
    const header = 'theme=dark; cf_at=abc%2Edef; cf_at_old=zzz';
    expect(readCookie(header, 'cf_at')).toBe('abc.def');
  });

  it('없거나 헤더가 없으면 null', () => {
    expect(readCookie('a=1', 'cf_at')).toBeNull();
    expect(readCookie(null, 'cf_at')).toBeNull();
  });

  it('값에 = 가 있어도 첫 = 뒤 전체를 값으로 본다', () => {
    expect(readCookie('cf_at=a=b==', 'cf_at')).toBe('a=b==');
  });

  it('깨진 퍼센트 인코딩은 없는 쿠키로 본다', () => {
    expect(readCookie('cf_at=%E0%A4%A', 'cf_at')).toBeNull();
  });
});

describe('serializeCookie', () => {
  it('HttpOnly · SameSite=Lax · Path=/ 와 Max-Age 를 붙인다', () => {
    expect(serializeCookie('cf_at', 'a.b', 1800, false)).toBe(
      'cf_at=a.b; Path=/; HttpOnly; SameSite=Lax; Max-Age=1800',
    );
  });

  it('secure 면 Secure 를 붙인다', () => {
    expect(serializeCookie('cf_at', 'v', 0, true)).toBe(
      'cf_at=v; Path=/; HttpOnly; SameSite=Lax; Max-Age=0; Secure',
    );
  });
});
```

Run: `pnpm vitest run packages/api-client/src/server/cookies.test.ts` → FAIL

- [ ] **Step 4: `src/server/cookies.ts`**

```ts
// 인증 쿠키 이름·수명은 앱이 넘긴다. 정확한 값은 #25(고객) · #29(어드민)에서 확정한다.
export type AuthCookieConfig = {
  accessName: string;
  refreshName: string;
  accessMaxAgeSec: number;
  refreshMaxAgeSec: number;
  /** 운영(https)에서는 true. 로컬 http 에서는 false. */
  secure: boolean;
};

export function readCookie(cookieHeader: string | null, name: string): string | null {
  if (!cookieHeader) return null;
  for (const part of cookieHeader.split(';')) {
    const index = part.indexOf('=');
    if (index === -1) continue;
    if (part.slice(0, index).trim() !== name) continue;
    try {
      return decodeURIComponent(part.slice(index + 1).trim());
    } catch {
      return null;
    }
  }
  return null;
}

export function serializeCookie(
  name: string,
  value: string,
  maxAgeSec: number,
  secure: boolean,
): string {
  const attributes = [
    `${name}=${encodeURIComponent(value)}`,
    'Path=/',
    'HttpOnly',
    'SameSite=Lax',
    `Max-Age=${maxAgeSec}`,
  ];
  if (secure) attributes.push('Secure');
  return attributes.join('; ');
}
```

Run: 같은 명령 → PASS

- [ ] **Step 5: `src/server/reissue.ts`**

(동작은 Step 6 의 프록시 테스트가 검증한다.)

```ts
import { isApiEnvelope } from '../envelope';
import { joinUrl } from './url';

export type TokenPair = {
  accessToken: string;
  refreshToken: string;
};

export const REISSUE_PATH = '/api/v1/auth/token/reissue';

// 같은 refresh 토큰으로 동시에 들어온 재발급은 한 번만 보낸다(인스턴스 내부 한정).
// 다중 인스턴스 경합은 BE refresh 회전 정책과 함께 #25 에서 정한다.
const inFlight = new Map<string, Promise<TokenPair | null>>();

/**
 * 요청 `{ refreshToken }` → dataBody `{ accessToken, refreshToken }` 은 #4 기준 가정이다.
 * #4 가 머지되면 실제 계약에 맞춘다. 실패는 모두 null 이다(호출자가 쿠키를 지운다).
 */
export function reissueTokens(
  gatewayUrl: string,
  refreshToken: string,
  fetchImpl: typeof fetch,
): Promise<TokenPair | null> {
  const running = inFlight.get(refreshToken);
  if (running) return running;

  const task = requestReissue(gatewayUrl, refreshToken, fetchImpl).finally(() => {
    inFlight.delete(refreshToken);
  });
  inFlight.set(refreshToken, task);
  return task;
}

async function requestReissue(
  gatewayUrl: string,
  refreshToken: string,
  fetchImpl: typeof fetch,
): Promise<TokenPair | null> {
  try {
    const response = await fetchImpl(joinUrl(gatewayUrl, REISSUE_PATH), {
      method: 'POST',
      headers: { 'content-type': 'application/json', accept: 'application/json' },
      body: JSON.stringify({ refreshToken }),
      cache: 'no-store',
    });
    if (!response.ok) return null;
    const payload: unknown = await response.json();
    if (!isApiEnvelope(payload) || !payload.dataHeader.success) return null;
    const body = payload.dataBody as Partial<TokenPair> | null;
    if (typeof body?.accessToken !== 'string' || typeof body.refreshToken !== 'string') return null;
    return { accessToken: body.accessToken, refreshToken: body.refreshToken };
  } catch {
    return null;
  }
}
```

- [ ] **Step 6: 실패하는 테스트 — `src/server/proxy.test.ts`**

```ts
import { describe, expect, it, vi } from 'vitest';
import type { AuthCookieConfig } from './cookies';
import { createProxyHandler } from './proxy';
import { REISSUE_PATH } from './reissue';

const GATEWAY = 'http://gateway.test:8000';
const COOKIES: AuthCookieConfig = {
  accessName: 'cf_at',
  refreshName: 'cf_rt',
  accessMaxAgeSec: 1800,
  refreshMaxAgeSec: 1209600,
  secure: false,
};

const envelope = (status: number, success: boolean, body: unknown = null, code: string | null = null) =>
  new Response(
    JSON.stringify({
      dataHeader: { success, resultCode: code, resultMessage: null, fieldErrors: null },
      dataBody: body,
    }),
    { status, headers: { 'content-type': 'application/json' } },
  );

const unauthorized = () => envelope(401, false, null, 'SECURITY_001');

const request = (path: string, init: RequestInit & { cookie?: string } = {}) => {
  const headers = new Headers(init.headers);
  if (init.cookie) headers.set('cookie', init.cookie);
  return new Request(`http://localhost:3000${path}`, { ...init, headers });
};

const urlOf = (input: RequestInfo | URL) => (typeof input === 'string' ? input : input.toString());
const authOf = (init?: RequestInit) => new Headers(init?.headers).get('authorization');

describe('createProxyHandler', () => {
  it('/api/v1 밖 경로는 게이트웨이로 보내지 않고 BFF_001 404 다', async () => {
    const fetchImpl = vi.fn<typeof fetch>();
    const handler = createProxyHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

    const res = await handler(request('/api/internal/x'));

    expect(res.status).toBe(404);
    expect(await res.json()).toMatchObject({ dataHeader: { success: false, resultCode: 'BFF_001' } });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('경로·쿼리·메서드·본문을 유지하고, 허용 헤더만 넘기고, 쿠키를 Authorization 으로 바꾼다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(envelope(200, true, { ok: 1 }));
    const handler = createProxyHandler({ gatewayUrl: `${GATEWAY}/`, cookies: COOKIES, fetch: fetchImpl });

    const res = await handler(
      request('/api/v1/orders?page=0', {
        method: 'POST',
        body: '{"qty":1}',
        cookie: 'cf_at=access-1; cf_rt=refresh-1; other=x',
        headers: {
          'content-type': 'application/json',
          'idempotency-key': 'key-1',
          'accept-language': 'ko-KR',
          authorization: 'Bearer forged',
          'x-forwarded-for': '1.2.3.4',
        },
      }),
    );

    expect(res.status).toBe(200);
    const [url, init] = fetchImpl.mock.calls[0]!;
    expect(urlOf(url)).toBe(`${GATEWAY}/api/v1/orders?page=0`);
    expect(init?.method).toBe('POST');
    expect(new TextDecoder().decode(init?.body as ArrayBuffer)).toBe('{"qty":1}');
    const headers = new Headers(init?.headers);
    expect(headers.get('authorization')).toBe('Bearer access-1');
    expect(headers.get('content-type')).toBe('application/json');
    expect(headers.get('idempotency-key')).toBe('key-1');
    expect(headers.get('accept-language')).toBe('ko-KR');
    expect(headers.has('cookie')).toBe(false);
    expect(headers.has('x-forwarded-for')).toBe(false);
  });

  it('access 쿠키가 없으면 Authorization 을 붙이지 않는다 (클라이언트가 보낸 값도 버린다)', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(envelope(200, true, []));
    const handler = createProxyHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

    await handler(request('/api/v1/products', { headers: { authorization: 'Bearer forged' } }));

    expect(authOf(fetchImpl.mock.calls[0]![1])).toBeNull();
  });

  it('게이트웨이 응답의 Set-Cookie 는 버리고 상태·본문은 그대로 중계한다', async () => {
    const upstream = envelope(404, false, null, 'ORDER_001');
    upstream.headers.append('set-cookie', 'leak=1');
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(upstream);
    const handler = createProxyHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

    const res = await handler(request('/api/v1/orders/1'));

    expect(res.status).toBe(404);
    expect(res.headers.getSetCookie()).toEqual([]);
    expect(await res.json()).toMatchObject({ dataHeader: { resultCode: 'ORDER_001' } });
  });

  it('401 이면 재발급 후 새 토큰으로 한 번 재시도하고 새 쿠키를 심는다', async () => {
    const fetchImpl = vi.fn<typeof fetch>(async (input, init) => {
      if (urlOf(input).endsWith(REISSUE_PATH)) {
        expect(JSON.parse(init?.body as string)).toEqual({ refreshToken: 'refresh-1' });
        return envelope(200, true, { accessToken: 'access-2', refreshToken: 'refresh-2' });
      }
      return authOf(init) === 'Bearer access-2' ? envelope(200, true, { id: 1 }) : unauthorized();
    });
    const handler = createProxyHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

    const res = await handler(request('/api/v1/members/me', { cookie: 'cf_at=access-1; cf_rt=refresh-1' }));

    expect(res.status).toBe(200);
    expect(fetchImpl).toHaveBeenCalledTimes(3);
    expect(res.headers.getSetCookie()).toEqual([
      'cf_at=access-2; Path=/; HttpOnly; SameSite=Lax; Max-Age=1800',
      'cf_rt=refresh-2; Path=/; HttpOnly; SameSite=Lax; Max-Age=1209600',
    ]);
  });

  it('재발급이 실패하면 인증 쿠키를 지우고 401 을 그대로 돌려준다', async () => {
    const fetchImpl = vi.fn<typeof fetch>(async (input) =>
      urlOf(input).endsWith(REISSUE_PATH) ? envelope(401, false, null, 'SECURITY_002') : unauthorized(),
    );
    const handler = createProxyHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

    const res = await handler(request('/api/v1/members/me', { cookie: 'cf_at=a; cf_rt=r' }));

    expect(res.status).toBe(401);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    expect(res.headers.getSetCookie()).toEqual([
      'cf_at=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0',
      'cf_rt=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0',
    ]);
  });

  it('refresh 쿠키가 없으면 재발급하지 않고 401 을 중계한다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(unauthorized());
    const handler = createProxyHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

    const res = await handler(request('/api/v1/members/me', { cookie: 'cf_at=a' }));

    expect(res.status).toBe(401);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it('같은 refresh 토큰의 동시 401 은 재발급을 한 번만 보낸다', async () => {
    let reissueCalls = 0;
    const fetchImpl = vi.fn<typeof fetch>(async (input, init) => {
      if (urlOf(input).endsWith(REISSUE_PATH)) {
        reissueCalls += 1;
        await new Promise((resolve) => setTimeout(resolve, 10));
        return envelope(200, true, { accessToken: 'access-9', refreshToken: 'refresh-9' });
      }
      return authOf(init) === 'Bearer access-9' ? envelope(200, true, {}) : unauthorized();
    });
    const handler = createProxyHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });
    const cookie = 'cf_at=old; cf_rt=refresh-shared';

    const [a, b] = await Promise.all([
      handler(request('/api/v1/a', { cookie })),
      handler(request('/api/v1/b', { cookie })),
    ]);

    expect([a.status, b.status]).toEqual([200, 200]);
    expect(reissueCalls).toBe(1);
  });

  it('게이트웨이 연결 실패는 BFF_002 503 이다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('fetch failed'));
    const handler = createProxyHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

    const res = await handler(request('/api/v1/products'));

    expect(res.status).toBe(503);
    expect(await res.json()).toMatchObject({ dataHeader: { resultCode: 'BFF_002' } });
  });

  it('재발급 성공 뒤 재시도가 연결 실패해도 새 쿠키는 심는다 (회전된 refresh 를 잃지 않는다)', async () => {
    const fetchImpl = vi.fn<typeof fetch>(async (input, init) => {
      if (urlOf(input).endsWith(REISSUE_PATH)) {
        return envelope(200, true, { accessToken: 'access-3', refreshToken: 'refresh-3' });
      }
      if (authOf(init) === 'Bearer access-3') throw new TypeError('fetch failed');
      return unauthorized();
    });
    const handler = createProxyHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

    const res = await handler(request('/api/v1/members/me', { cookie: 'cf_at=a; cf_rt=refresh-x' }));

    expect(res.status).toBe(503);
    expect(res.headers.getSetCookie()).toEqual([
      'cf_at=access-3; Path=/; HttpOnly; SameSite=Lax; Max-Age=1800',
      'cf_rt=refresh-3; Path=/; HttpOnly; SameSite=Lax; Max-Age=1209600',
    ]);
  });
});
```

Run: `pnpm vitest run packages/api-client/src/server/proxy.test.ts` → FAIL (모듈 없음)

- [ ] **Step 7: `src/server/proxy.ts`**

```ts
import { errorEnvelope } from '../envelope';
import { readCookie, serializeCookie, type AuthCookieConfig } from './cookies';
import { reissueTokens, type TokenPair } from './reissue';
import { joinUrl } from './url';

export type ProxyOptions = {
  gatewayUrl: string;
  cookies: AuthCookieConfig;
  fetch?: typeof fetch;
};

const API_PREFIX = '/api/v1/';
// 게이트웨이로 넘기는 요청 헤더. Cookie·Authorization·X-Forwarded-* 등은 넘기지 않는다.
const FORWARD_REQUEST_HEADERS = ['content-type', 'accept', 'accept-language', 'idempotency-key'];
// 중계하지 않는 응답 헤더. fetch 가 본문을 이미 풀었으므로 인코딩·길이 헤더도 버린다.
const DROP_RESPONSE_HEADERS = new Set([
  'set-cookie',
  'content-encoding',
  'content-length',
  'transfer-encoding',
  'connection',
  'keep-alive',
]);

/**
 * 브라우저 → Next(/api/v1/**) → 게이트웨이 중계 핸들러.
 * httpOnly 쿠키의 access 토큰을 Authorization 으로 바꾸고, 401 이면 한 번 재발급·재시도한다.
 * 401 은 인증 단계 거절이라 서버가 처리하지 않았으므로 쓰기 요청도 재시도가 안전하다.
 */
export function createProxyHandler(options: ProxyOptions): (request: Request) => Promise<Response> {
  const fetchImpl = options.fetch ?? fetch;
  const { cookies } = options;

  return async (request) => {
    const url = new URL(request.url);
    if (!url.pathname.startsWith(API_PREFIX)) {
      return Response.json(errorEnvelope('BFF_001', '존재하지 않는 경로입니다.'), { status: 404 });
    }

    // 재시도에 다시 써야 하므로 본문을 한 번 버퍼에 담는다.
    const body =
      request.method === 'GET' || request.method === 'HEAD' ? undefined : await request.arrayBuffer();
    const cookieHeader = request.headers.get('cookie');
    const accessToken = readCookie(cookieHeader, cookies.accessName);
    const refreshToken = readCookie(cookieHeader, cookies.refreshName);
    const target = joinUrl(options.gatewayUrl, url.pathname + url.search);

    const send = (token: string | null) =>
      fetchImpl(target, {
        method: request.method,
        headers: forwardHeaders(request.headers, token),
        body,
        redirect: 'manual',
        cache: 'no-store',
      });

    let issued: TokenPair | null = null;
    try {
      const first = await send(accessToken);
      if (first.status !== 401 || !refreshToken) return relay(first, []);

      issued = await reissueTokens(options.gatewayUrl, refreshToken, fetchImpl);
      if (!issued) return relay(first, clearCookies(cookies));

      await first.body?.cancel();
      const retried = await send(issued.accessToken);
      return relay(retried, issueCookies(cookies, issued));
    } catch {
      const response = Response.json(
        errorEnvelope('BFF_002', '서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.'),
        { status: 503 },
      );
      // 재발급은 성공했다면 회전된 refresh 토큰을 잃지 않도록 쿠키는 심는다.
      if (issued) {
        for (const cookie of issueCookies(cookies, issued)) response.headers.append('set-cookie', cookie);
      }
      return response;
    }
  };
}

function forwardHeaders(source: Headers, accessToken: string | null): Headers {
  const headers = new Headers();
  for (const name of FORWARD_REQUEST_HEADERS) {
    const value = source.get(name);
    if (value !== null) headers.set(name, value);
  }
  if (accessToken) headers.set('authorization', `Bearer ${accessToken}`);
  return headers;
}

function relay(upstream: Response, setCookies: string[]): Response {
  const headers = new Headers();
  upstream.headers.forEach((value, name) => {
    if (!DROP_RESPONSE_HEADERS.has(name)) headers.set(name, value);
  });
  for (const cookie of setCookies) headers.append('set-cookie', cookie);
  return new Response(upstream.body, { status: upstream.status, headers });
}

function issueCookies(config: AuthCookieConfig, pair: TokenPair): string[] {
  return [
    serializeCookie(config.accessName, pair.accessToken, config.accessMaxAgeSec, config.secure),
    serializeCookie(config.refreshName, pair.refreshToken, config.refreshMaxAgeSec, config.secure),
  ];
}

function clearCookies(config: AuthCookieConfig): string[] {
  return [
    serializeCookie(config.accessName, '', 0, config.secure),
    serializeCookie(config.refreshName, '', 0, config.secure),
  ];
}
```

Run: `pnpm vitest run packages/api-client/src/server/proxy.test.ts` → PASS

- [ ] **Step 8: 실패하는 테스트 — `src/server/gateway-request.test.ts`**

```ts
import { describe, expect, it, vi } from 'vitest';
import { gatewayRequest } from './gateway-request';

const ok = (body: unknown) =>
  new Response(
    JSON.stringify({
      dataHeader: { success: true, resultCode: null, resultMessage: null, fieldErrors: null },
      dataBody: body,
    }),
    { status: 200 },
  );

describe('gatewayRequest', () => {
  it('게이트웨이를 직접 부르고 access 토큰을 Authorization 으로 붙인다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(ok({ id: 1 }));

    const result = await gatewayRequest<{ id: number }>('/api/v1/members/me', {
      gatewayUrl: 'http://gw:8000/',
      accessToken: 'access-1',
      fetch: fetchImpl,
    });

    expect(result).toEqual({ id: 1 });
    const [url, init] = fetchImpl.mock.calls[0]!;
    expect(url).toBe('http://gw:8000/api/v1/members/me');
    expect(new Headers(init?.headers).get('authorization')).toBe('Bearer access-1');
  });

  it('토큰이 없으면 Authorization 을 붙이지 않는다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(ok([]));
    await gatewayRequest('/api/v1/products', { gatewayUrl: 'http://gw:8000', fetch: fetchImpl });
    expect(new Headers(fetchImpl.mock.calls[0]![1]?.headers).has('authorization')).toBe(false);
  });

  it('연결 실패는 network ApiError 다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('fetch failed'));
    await expect(
      gatewayRequest('/api/v1/products', { gatewayUrl: 'http://gw:8000', fetch: fetchImpl }),
    ).rejects.toMatchObject({ kind: 'network' });
  });
});
```

Run: `pnpm vitest run packages/api-client/src/server/gateway-request.test.ts` → FAIL

- [ ] **Step 9: `src/server/gateway-request.ts`**

```ts
import { ApiError } from '../api-error';
import { unwrap } from '../unwrap';
import { joinUrl } from './url';

export type GatewayRequestOptions = {
  gatewayUrl: string;
  accessToken?: string | null;
  init?: RequestInit;
  fetch?: typeof fetch;
};

/**
 * Server Component 에서 게이트웨이를 직접 부른다(자기 BFF 를 거치지 않는다).
 * RSC 는 쿠키를 쓸 수 없어 재발급하지 않는다. 401 이면 unauthorized 를 던지고 호출자가 로그인으로 보낸다.
 */
export async function gatewayRequest<T>(path: string, options: GatewayRequestOptions): Promise<T> {
  const headers = new Headers(options.init?.headers);
  headers.set('accept', 'application/json');
  if (options.accessToken) headers.set('authorization', `Bearer ${options.accessToken}`);

  let response: Response;
  try {
    response = await (options.fetch ?? fetch)(joinUrl(options.gatewayUrl, path), {
      cache: 'no-store',
      ...options.init,
      headers,
    });
  } catch {
    throw new ApiError('network', null, null, null);
  }
  return unwrap<T>(response);
}
```

Run: 같은 명령 → PASS

- [ ] **Step 10: 실패하는 테스트 — `src/server/health.test.ts`**

```ts
import { describe, expect, it, vi } from 'vitest';
import { checkGateway, healthResponse } from './health';

describe('checkGateway', () => {
  it('actuator 가 UP 이면 UP', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ status: 'UP' }));
    await expect(checkGateway('http://gw:8000/', fetchImpl)).resolves.toBe('UP');
    expect(fetchImpl.mock.calls[0]![0]).toBe('http://gw:8000/actuator/health');
  });

  it.each([
    ['DOWN 응답', () => Response.json({ status: 'DOWN' }, { status: 503 })],
    ['200 이지만 UP 아님', () => Response.json({ status: 'OUT_OF_SERVICE' })],
    ['JSON 아님', () => new Response('oops', { status: 200 })],
  ])('%s → DOWN', async (_, make) => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(make());
    await expect(checkGateway('http://gw:8000', fetchImpl)).resolves.toBe('DOWN');
  });

  it('연결 실패 → DOWN', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('fetch failed'));
    await expect(checkGateway('http://gw:8000', fetchImpl)).resolves.toBe('DOWN');
  });
});

describe('healthResponse', () => {
  it('게이트웨이 UP 이면 200 { app: UP, gateway: UP }', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ status: 'UP' }));
    const res = await healthResponse('http://gw:8000', fetchImpl);
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ app: 'UP', gateway: 'UP' });
  });

  it('게이트웨이 DOWN 이면 503 { app: UP, gateway: DOWN }', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('fetch failed'));
    const res = await healthResponse('http://gw:8000', fetchImpl);
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ app: 'UP', gateway: 'DOWN' });
  });
});
```

Run: `pnpm vitest run packages/api-client/src/server/health.test.ts` → FAIL

- [ ] **Step 11: `src/server/health.ts`**

```ts
import { joinUrl } from './url';

export type HealthStatus = 'UP' | 'DOWN';

export type HealthReport = {
  app: HealthStatus;
  gateway: HealthStatus;
};

/** 게이트웨이 actuator 헬스체크. 어떤 실패든 DOWN 이다. */
export async function checkGateway(
  gatewayUrl: string,
  fetchImpl: typeof fetch = fetch,
  timeoutMs = 3000,
): Promise<HealthStatus> {
  try {
    const response = await fetchImpl(joinUrl(gatewayUrl, '/actuator/health'), {
      cache: 'no-store',
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (!response.ok) return 'DOWN';
    const body = (await response.json()) as { status?: unknown };
    return body.status === 'UP' ? 'UP' : 'DOWN';
  } catch {
    return 'DOWN';
  }
}

export async function healthResponse(
  gatewayUrl: string,
  fetchImpl: typeof fetch = fetch,
): Promise<Response> {
  const gateway = await checkGateway(gatewayUrl, fetchImpl);
  const report: HealthReport = { app: 'UP', gateway };
  return Response.json(report, {
    status: gateway === 'UP' ? 200 : 503,
    headers: { 'cache-control': 'no-store' },
  });
}
```

Run: 같은 명령 → PASS

- [ ] **Step 12: `src/server/index.ts`**

```ts
// 서버 전용 진입점. route handler · Server Component 에서만 import 한다. 클라이언트 컴포넌트에서 쓰지 않는다.
export { readCookie, serializeCookie, type AuthCookieConfig } from './cookies';
export { gatewayRequest, type GatewayRequestOptions } from './gateway-request';
export { checkGateway, healthResponse, type HealthReport, type HealthStatus } from './health';
export { createProxyHandler, type ProxyOptions } from './proxy';
export { REISSUE_PATH, reissueTokens, type TokenPair } from './reissue';
export { joinUrl } from './url';
```

- [ ] **Step 13: 전체 확인**

Run: `pnpm vitest run packages/api-client && pnpm --filter @cf/api-client typecheck && pnpm lint && pnpm format:check`
Expected: 모두 PASS

- [ ] **Step 14: Commit**

```bash
git add frontend/packages/api-client/src/server
git commit -m "[FE] feat: BFF 프록시·토큰 재발급·게이트웨이 헬스체크를 추가한다 (#23)"
```

---

### Task 4: `@cf/ui` 와 `@cf/theme-mokoyuri`

**Files:**
- Create: `frontend/packages/ui/package.json`, `tsconfig.json`, `src/index.ts`, `src/tokens.css`, `src/brand.ts`, `src/cx.ts`, `src/Button.tsx`, `src/FieldError.tsx`, `src/TextField.tsx`
- Test: `frontend/packages/ui/src/FieldError.test.tsx`, `src/TextField.test.tsx`
- Create: `frontend/packages/theme-mokoyuri/package.json`, `tsconfig.json`, `theme.css`, `src/brand.ts`

**Interfaces:**
- Produces (import `@cf/ui`):
  - `type BrandConfig = { name: string; logoSrc: string | null; defaultLocale: 'ko-KR' | 'en-US'; seo: { titleTemplate: string; description: string } }`
  - `cx(...classes: Array<string | false | null | undefined>): string`
  - `Button(props: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'secondary' })`
  - `FieldError(props: { id?: string; messages: readonly string[] })`
  - `TextField(props: Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> & { label: string; errors?: readonly string[] })`
  - CSS `@cf/ui/tokens.css` — 원시 토큰 `--cf-color-{bg,fg,muted,muted-fg,border,primary,primary-fg,danger}`, `--cf-radius-md`, `--cf-font-sans` → Tailwind 클래스 `bg-bg text-fg bg-muted text-muted-fg border-border bg-primary text-primary-fg text-danger rounded-md font-sans`
- Produces (import `@cf/brand` 별칭 → `@cf/theme-mokoyuri`): `brand: BrandConfig`, CSS `@cf/brand/theme.css`

- [ ] **Step 1: ui 패키지 파일**

`frontend/packages/ui/package.json`:

```json
{
  "name": "@cf/ui",
  "version": "0.0.0",
  "private": true,
  "type": "module",
  "exports": {
    ".": "./src/index.ts",
    "./tokens.css": {
      "style": "./src/tokens.css",
      "default": "./src/tokens.css"
    }
  },
  "scripts": {
    "typecheck": "tsc --noEmit"
  },
  "peerDependencies": {
    "react": "catalog:"
  },
  "devDependencies": {
    "@types/react": "catalog:",
    "@types/react-dom": "catalog:",
    "react": "catalog:",
    "react-dom": "catalog:",
    "typescript": "catalog:",
    "vitest": "catalog:"
  }
}
```

`frontend/packages/ui/tsconfig.json`:

```json
{
  "extends": "../../tsconfig.base.json",
  "include": ["src"]
}
```

`frontend/packages/ui/src/brand.ts`:

```ts
/** 브랜드 테마 패키지(theme-<brand>)가 채우는 설정. 고객사 실정보(사업자번호 등)는 넣지 않는다. */
export type BrandConfig = {
  name: string;
  logoSrc: string | null;
  defaultLocale: 'ko-KR' | 'en-US';
  seo: {
    /** 예: '%s | 브랜드명' */
    titleTemplate: string;
    description: string;
  };
};
```

`frontend/packages/ui/src/cx.ts`:

```ts
export function cx(...classes: Array<string | false | null | undefined>): string {
  return classes.filter(Boolean).join(' ');
}
```

`frontend/packages/ui/src/tokens.css`:

```css
/*
 * 브랜드 무관 중립 토큰. 브랜드 테마(theme-<brand>/theme.css)는 --cf-* 값만 덮어쓴다.
 * 컴포넌트는 아래 @theme 로 연결된 클래스(bg-primary 등)만 쓴다. frontend/docs/styling-theme.md
 */
:root {
  --cf-color-bg: #ffffff;
  --cf-color-fg: #111827;
  --cf-color-muted: #f3f4f6;
  --cf-color-muted-fg: #6b7280;
  --cf-color-border: #e5e7eb;
  --cf-color-primary: #111827;
  --cf-color-primary-fg: #ffffff;
  --cf-color-danger: #dc2626;
  --cf-radius-md: 0.5rem;
  --cf-font-sans:
    ui-sans-serif, system-ui, -apple-system, 'Apple SD Gothic Neo', 'Malgun Gothic', sans-serif;
}

@theme inline {
  --color-bg: var(--cf-color-bg);
  --color-fg: var(--cf-color-fg);
  --color-muted: var(--cf-color-muted);
  --color-muted-fg: var(--cf-color-muted-fg);
  --color-border: var(--cf-color-border);
  --color-primary: var(--cf-color-primary);
  --color-primary-fg: var(--cf-color-primary-fg);
  --color-danger: var(--cf-color-danger);
  --radius-md: var(--cf-radius-md);
  --font-sans: var(--cf-font-sans);
}
```

Run: `pnpm install`

- [ ] **Step 2: 실패하는 테스트 — `src/FieldError.test.tsx`, `src/TextField.test.tsx`**

`frontend/packages/ui/src/FieldError.test.tsx`:

```tsx
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { FieldError } from './FieldError';

describe('FieldError', () => {
  it('메시지가 없으면 아무것도 그리지 않는다', () => {
    expect(renderToStaticMarkup(<FieldError messages={[]} />)).toBe('');
  });

  it('메시지를 role=alert 목록으로 그린다 (중복 메시지 포함)', () => {
    const html = renderToStaticMarkup(
      <FieldError id="phone-error" messages={['형식 오류', '형식 오류']} />,
    );
    expect(html).toContain('id="phone-error"');
    expect(html).toContain('role="alert"');
    expect(html.match(/<li>형식 오류<\/li>/g)).toHaveLength(2);
  });
});
```

`frontend/packages/ui/src/TextField.test.tsx`:

```tsx
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { TextField } from './TextField';

describe('TextField', () => {
  it('label 과 input 을 연결한다', () => {
    const html = renderToStaticMarkup(<TextField label="휴대폰 번호" name="phone" />);
    const inputId = html.match(/<input[^>]*id="([^"]+)"/)?.[1];
    expect(inputId).toBeTruthy();
    expect(html).toContain(`for="${inputId}"`);
    expect(html).not.toContain('aria-invalid');
  });

  it('오류가 있으면 aria-invalid 와 aria-describedby 로 오류 목록을 가리킨다', () => {
    const html = renderToStaticMarkup(
      <TextField label="휴대폰 번호" name="phone" errors={['형식 오류']} />,
    );
    const describedBy = html.match(/aria-describedby="([^"]+)"/)?.[1];
    expect(html).toContain('aria-invalid="true"');
    expect(describedBy).toBeTruthy();
    expect(html).toContain(`id="${describedBy}"`);
    expect(html).toContain('형식 오류');
  });
});
```

Run: `pnpm vitest run packages/ui` → FAIL (모듈 없음)

- [ ] **Step 3: 컴포넌트**

`frontend/packages/ui/src/FieldError.tsx`:

```tsx
export type FieldErrorProps = {
  id?: string;
  messages: readonly string[];
};

/** 필드 단위 검증 오류(fieldErrors) 표시. 메시지가 없으면 그리지 않는다. */
export function FieldError({ id, messages }: FieldErrorProps) {
  if (messages.length === 0) return null;
  return (
    <ul id={id} role="alert" className="mt-1 space-y-0.5 text-sm text-danger">
      {messages.map((message, index) => (
        <li key={`${index}-${message}`}>{message}</li>
      ))}
    </ul>
  );
}
```

`frontend/packages/ui/src/TextField.tsx`:

```tsx
import { useId, type InputHTMLAttributes } from 'react';
import { cx } from './cx';
import { FieldError } from './FieldError';

export type TextFieldProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> & {
  label: string;
  errors?: readonly string[];
};

export function TextField({ label, errors = [], className, ...rest }: TextFieldProps) {
  const id = useId();
  const errorId = `${id}-error`;
  const invalid = errors.length > 0;

  return (
    <div className={cx('flex flex-col gap-1', className)}>
      <label htmlFor={id} className="text-sm font-medium text-fg">
        {label}
      </label>
      <input
        id={id}
        aria-invalid={invalid || undefined}
        aria-describedby={invalid ? errorId : undefined}
        className={cx(
          'h-10 rounded-md border bg-bg px-3 text-fg outline-none focus:ring-2 focus:ring-primary',
          invalid ? 'border-danger' : 'border-border',
        )}
        {...rest}
      />
      <FieldError id={errorId} messages={errors} />
    </div>
  );
}
```

`frontend/packages/ui/src/Button.tsx`:

```tsx
import type { ButtonHTMLAttributes } from 'react';
import { cx } from './cx';

type Variant = 'primary' | 'secondary';

export type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: Variant;
};

const VARIANT_CLASS: Record<Variant, string> = {
  primary: 'bg-primary text-primary-fg hover:opacity-90',
  secondary: 'border border-border bg-bg text-fg hover:bg-muted',
};

/** 기본 type 은 button 이다. 폼 제출은 type="submit" 을 명시한다. */
export function Button({ variant = 'primary', className, type = 'button', ...rest }: ButtonProps) {
  return (
    <button
      type={type}
      className={cx(
        'inline-flex h-10 items-center justify-center rounded-md px-4 text-sm font-medium transition disabled:cursor-not-allowed disabled:opacity-50',
        VARIANT_CLASS[variant],
        className,
      )}
      {...rest}
    />
  );
}
```

`frontend/packages/ui/src/index.ts`:

```ts
export type { BrandConfig } from './brand';
export { Button, type ButtonProps } from './Button';
export { cx } from './cx';
export { FieldError, type FieldErrorProps } from './FieldError';
export { TextField, type TextFieldProps } from './TextField';
```

Run: `pnpm vitest run packages/ui`
Expected: PASS. JSX 변환 오류(`React is not defined` 또는 `oxc` 옵션 미인식)가 나면 `pnpm ls vite` 로 Vite 메이저를 확인하고, 7 이하면 `vitest.config.ts` 의 `oxc` 줄을 `esbuild: { jsx: 'automatic' },` 로 바꾼다.

- [ ] **Step 4: 테마 패키지**

`frontend/packages/theme-mokoyuri/package.json`:

```json
{
  "name": "@cf/theme-mokoyuri",
  "version": "0.0.0",
  "private": true,
  "type": "module",
  "exports": {
    ".": "./src/brand.ts",
    "./theme.css": {
      "style": "./theme.css",
      "default": "./theme.css"
    }
  },
  "scripts": {
    "typecheck": "tsc --noEmit"
  },
  "dependencies": {
    "@cf/ui": "workspace:*"
  },
  "devDependencies": {
    "typescript": "catalog:"
  }
}
```

`frontend/packages/theme-mokoyuri/tsconfig.json`:

```json
{
  "extends": "../../tsconfig.base.json",
  "include": ["src"]
}
```

`frontend/packages/theme-mokoyuri/theme.css`:

```css
/*
 * MOKOYURI 임시 팔레트 — 브랜드 가이드를 받으면 값만 바꾼다.
 * --cf-* 원시 토큰만 덮어쓴다. 토큰 이름은 @cf/ui/tokens.css 가 정본이다.
 */
:root {
  --cf-color-bg: #fbf8f3; /* 한지 아이보리 */
  --cf-color-fg: #2b2622;
  --cf-color-muted: #f1ebe2;
  --cf-color-muted-fg: #7a6f64;
  --cf-color-border: #e3d9cc;
  --cf-color-primary: #2f3e5c; /* 쪽빛 */
  --cf-color-primary-fg: #ffffff;
  --cf-color-danger: #b42318;
  --cf-radius-md: 0.25rem;
}
```

`frontend/packages/theme-mokoyuri/src/brand.ts`:

```ts
import type { BrandConfig } from '@cf/ui';

export const brand: BrandConfig = {
  name: 'MOKOYURI',
  logoSrc: null,
  defaultLocale: 'ko-KR',
  seo: {
    titleTemplate: '%s | MOKOYURI',
    description: '생활한복 MOKOYURI 온라인 스토어',
  },
};
```

Run: `pnpm install && pnpm typecheck && pnpm lint && pnpm format:check`
Expected: 오류 없음

- [ ] **Step 5: Commit**

```bash
git add frontend/packages/ui frontend/packages/theme-mokoyuri frontend/pnpm-lock.yaml
git commit -m "[FE] feat: 공통 UI 토큰·폼 컴포넌트와 MOKOYURI 임시 테마를 추가한다 (#23)"
```

---

### Task 5: 스토어프론트 앱

**Files:**
- Create: `frontend/apps/storefront/package.json`, `tsconfig.json`, `next.config.ts`, `postcss.config.mjs`
- Create: `src/app/layout.tsx`, `src/app/page.tsx`, `src/app/globals.css`, `src/app/api/[...path]/route.ts`, `src/app/api/health/route.ts`
- Create: `src/lib/env.ts`, `src/lib/auth-cookies.ts`, `src/lib/server-api.ts`

**Interfaces:**
- Consumes: `createProxyHandler`, `healthResponse`, `checkGateway`, `gatewayRequest`, `AuthCookieConfig` (`@cf/api-client/server`), `brand` (`@cf/brand`), `@cf/ui/tokens.css`, `@cf/brand/theme.css`
- Produces: `GET /api/health`, `/api/v1/**` 프록시(GET·POST·PUT·PATCH·DELETE), `serverFetch<T>(path, init?)`

- [ ] **Step 1: 패키지·설정 파일**

`frontend/apps/storefront/package.json`:

```json
{
  "name": "@cf/storefront",
  "version": "0.0.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "next dev --port 3000",
    "build": "next build",
    "start": "next start --port 3000",
    "typecheck": "next typegen && tsc --noEmit"
  },
  "dependencies": {
    "@cf/api-client": "workspace:*",
    "@cf/brand": "workspace:@cf/theme-mokoyuri@*",
    "@cf/ui": "workspace:*",
    "next": "catalog:",
    "react": "catalog:",
    "react-dom": "catalog:"
  },
  "devDependencies": {
    "@tailwindcss/postcss": "catalog:",
    "@types/node": "catalog:",
    "@types/react": "catalog:",
    "@types/react-dom": "catalog:",
    "tailwindcss": "catalog:",
    "typescript": "catalog:"
  }
}
```

`frontend/apps/storefront/tsconfig.json`:

```json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": {
    "types": ["node"],
    "allowJs": true,
    "incremental": true,
    "plugins": [{ "name": "next" }],
    "paths": {
      "@/*": ["./src/*"]
    }
  },
  "include": [
    "next-env.d.ts",
    "src/**/*.ts",
    "src/**/*.tsx",
    ".next/types/**/*.ts",
    ".next/dev/types/**/*.ts"
  ],
  "exclude": ["node_modules"]
}
```

`frontend/apps/storefront/next.config.ts`:

```ts
import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  // 워크스페이스 패키지는 빌드하지 않고 TS 소스를 내보낸다.
  transpilePackages: ['@cf/api-client', '@cf/ui', '@cf/brand'],
  poweredByHeader: false,
};

export default nextConfig;
```

`frontend/apps/storefront/postcss.config.mjs`:

```js
export default {
  plugins: {
    '@tailwindcss/postcss': {},
  },
};
```

Run: `pnpm install`
Expected: `apps/storefront/node_modules/@cf/brand` 가 `packages/theme-mokoyuri` 를 가리킨다 — 확인: `ls -l apps/storefront/node_modules/@cf/`

- [ ] **Step 2: `src/lib/*`**

`frontend/apps/storefront/src/lib/env.ts`:

```ts
/** 서버 전용. 브라우저는 게이트웨이를 직접 부르지 않는다. */
export const gatewayUrl = process.env.GATEWAY_URL ?? 'http://localhost:8000';
```

`frontend/apps/storefront/src/lib/auth-cookies.ts`:

```ts
import type { AuthCookieConfig } from '@cf/api-client/server';

// 쿠키 이름·수명은 #25 에서 BE 토큰 만료와 맞춰 확정한다. 지금 값은 임시다.
export const authCookies: AuthCookieConfig = {
  accessName: 'cf_at',
  refreshName: 'cf_rt',
  accessMaxAgeSec: 60 * 30,
  refreshMaxAgeSec: 60 * 60 * 24 * 14,
  secure: process.env.NODE_ENV === 'production',
};
```

`frontend/apps/storefront/src/lib/server-api.ts`:

```ts
import { gatewayRequest } from '@cf/api-client/server';
import { cookies } from 'next/headers';
import { authCookies } from './auth-cookies';
import { gatewayUrl } from './env';

/** Server Component 용. 재발급하지 않는다 — 401 이면 ApiError(unauthorized) 를 던진다. */
export async function serverFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const store = await cookies();
  return gatewayRequest<T>(path, {
    gatewayUrl,
    accessToken: store.get(authCookies.accessName)?.value ?? null,
    init,
  });
}
```

- [ ] **Step 3: route handler**

`frontend/apps/storefront/src/app/api/[...path]/route.ts`:

```ts
import { createProxyHandler } from '@cf/api-client/server';
import { authCookies } from '@/lib/auth-cookies';
import { gatewayUrl } from '@/lib/env';

const handler = createProxyHandler({ gatewayUrl, cookies: authCookies });

export const dynamic = 'force-dynamic';
export { handler as DELETE, handler as GET, handler as PATCH, handler as POST, handler as PUT };
```

`frontend/apps/storefront/src/app/api/health/route.ts`:

```ts
import { healthResponse } from '@cf/api-client/server';
import { gatewayUrl } from '@/lib/env';

export const dynamic = 'force-dynamic';

export function GET(): Promise<Response> {
  return healthResponse(gatewayUrl);
}
```

- [ ] **Step 4: 레이아웃·페이지·스타일**

`frontend/apps/storefront/src/app/globals.css`:

```css
@import 'tailwindcss';
@import '@cf/ui/tokens.css';
@import '@cf/brand/theme.css';

/* 공유 컴포넌트의 클래스를 스캔한다 (frontend/ 기준 packages/ui/src) */
@source '../../../../packages/ui/src';
```

`frontend/apps/storefront/src/app/layout.tsx`:

```tsx
import { brand } from '@cf/brand';
import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import './globals.css';

export const metadata: Metadata = {
  title: { default: brand.name, template: brand.seo.titleTemplate },
  description: brand.seo.description,
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang={brand.defaultLocale === 'ko-KR' ? 'ko' : 'en'}>
      <body className="bg-bg font-sans text-fg antialiased">{children}</body>
    </html>
  );
}
```

`frontend/apps/storefront/src/app/page.tsx`:

```tsx
import { checkGateway } from '@cf/api-client/server';
import { brand } from '@cf/brand';
import { gatewayUrl } from '@/lib/env';

export const dynamic = 'force-dynamic';

// 임시 화면 — #24 에서 메인 화면으로 바꾼다.
export default async function HomePage() {
  const gateway = await checkGateway(gatewayUrl);

  return (
    <main className="mx-auto flex min-h-dvh max-w-xl flex-col justify-center gap-4 px-4">
      <h1 className="text-2xl font-semibold">{brand.name}</h1>
      <p className="text-muted-fg">스토어프론트 초기 구성</p>
      <p>
        게이트웨이:{' '}
        <span className={gateway === 'UP' ? 'font-medium text-primary' : 'font-medium text-danger'}>
          {gateway}
        </span>
      </p>
    </main>
  );
}
```

- [ ] **Step 5: 검사·빌드**

Run: `pnpm --filter @cf/storefront typecheck && pnpm lint && pnpm format:check && pnpm --filter @cf/storefront build`
Expected: 모두 성공. `next build` 가 `tsconfig.json` 을 고쳤다는 메시지를 내면 바뀐 내용을 확인하고 그대로 커밋에 포함한다. Turbopack 이 워크스페이스 루트를 잘못 추론한다는 경고(`multiple lockfiles` 등)가 나올 때만, `next.config.ts` 에 `import path from 'node:path';` 와 `turbopack: { root: path.resolve(process.cwd(), '../..') },` 를 더한다(`next build` 는 앱 디렉터리에서 실행되므로 `frontend/` 를 가리킨다). 경고가 없으면 더하지 않는다.

- [ ] **Step 6: dev 서버 확인**

Run (백그라운드): `pnpm --filter @cf/storefront dev`
Then: `curl -s -o /dev/null -w '%{http_code}\n' localhost:3000/` → `200`
Then: `curl -s localhost:3000/api/health` → 게이트웨이를 안 띄웠으면 `{"app":"UP","gateway":"DOWN"}` (HTTP 503)
Then: `curl -s localhost:3000/api/internal/x` → `BFF_001` 봉투 (HTTP 404)
Then: `curl -s localhost:3000/api/v1/products` → 게이트웨이 없음 → `BFF_002` 봉투 (HTTP 503)
dev 서버를 종료한다.

- [ ] **Step 7: Commit**

```bash
git add frontend/apps/storefront/package.json frontend/apps/storefront/tsconfig.json \
  frontend/apps/storefront/next.config.ts frontend/apps/storefront/postcss.config.mjs \
  frontend/apps/storefront/src frontend/pnpm-lock.yaml
git commit -m "[FE] feat: 스토어프론트 앱에 BFF 프록시·헬스체크·브랜드 테마를 연결한다 (#23)"
```

---

### Task 6: 어드민 앱

**Files:**
- Create: `frontend/apps/admin/package.json`, `tsconfig.json`, `next.config.ts`, `postcss.config.mjs`
- Create: `src/app/layout.tsx`, `src/app/page.tsx`, `src/app/globals.css`, `src/app/api/[...path]/route.ts`, `src/app/api/health/route.ts`
- Create: `src/lib/env.ts`, `src/lib/auth-cookies.ts`, `src/lib/server-api.ts`

**Interfaces:**
- Consumes: Task 5 와 같음. 단 `@cf/brand/theme.css` 는 import 하지 않는다(중립 테마). 쿠키 이름 `cf_admin_at` / `cf_admin_rt`. 포트 3001.

- [ ] **Step 1: 패키지·설정 파일**

`frontend/apps/admin/package.json`:

```json
{
  "name": "@cf/admin",
  "version": "0.0.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "next dev --port 3001",
    "build": "next build",
    "start": "next start --port 3001",
    "typecheck": "next typegen && tsc --noEmit"
  },
  "dependencies": {
    "@cf/api-client": "workspace:*",
    "@cf/brand": "workspace:@cf/theme-mokoyuri@*",
    "@cf/ui": "workspace:*",
    "next": "catalog:",
    "react": "catalog:",
    "react-dom": "catalog:"
  },
  "devDependencies": {
    "@tailwindcss/postcss": "catalog:",
    "@types/node": "catalog:",
    "@types/react": "catalog:",
    "@types/react-dom": "catalog:",
    "tailwindcss": "catalog:",
    "typescript": "catalog:"
  }
}
```

`frontend/apps/admin/tsconfig.json`:

```json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": {
    "types": ["node"],
    "allowJs": true,
    "incremental": true,
    "plugins": [{ "name": "next" }],
    "paths": {
      "@/*": ["./src/*"]
    }
  },
  "include": [
    "next-env.d.ts",
    "src/**/*.ts",
    "src/**/*.tsx",
    ".next/types/**/*.ts",
    ".next/dev/types/**/*.ts"
  ],
  "exclude": ["node_modules"]
}
```

`frontend/apps/admin/next.config.ts`:

```ts
import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  // 워크스페이스 패키지는 빌드하지 않고 TS 소스를 내보낸다.
  transpilePackages: ['@cf/api-client', '@cf/ui', '@cf/brand'],
  poweredByHeader: false,
};

export default nextConfig;
```

`frontend/apps/admin/postcss.config.mjs`:

```js
export default {
  plugins: {
    '@tailwindcss/postcss': {},
  },
};
```

Run: `pnpm install`

- [ ] **Step 2: `src/lib/*`**

`frontend/apps/admin/src/lib/env.ts`:

```ts
/** 서버 전용. 브라우저는 게이트웨이를 직접 부르지 않는다. */
export const gatewayUrl = process.env.GATEWAY_URL ?? 'http://localhost:8000';
```

`frontend/apps/admin/src/lib/auth-cookies.ts`:

```ts
import type { AuthCookieConfig } from '@cf/api-client/server';

// 고객 세션과 섞이지 않도록 이름을 따로 둔다. 이름·수명은 #29 에서 확정한다. 지금 값은 임시다.
export const authCookies: AuthCookieConfig = {
  accessName: 'cf_admin_at',
  refreshName: 'cf_admin_rt',
  accessMaxAgeSec: 60 * 30,
  refreshMaxAgeSec: 60 * 60 * 12,
  secure: process.env.NODE_ENV === 'production',
};
```

`frontend/apps/admin/src/lib/server-api.ts`:

```ts
import { gatewayRequest } from '@cf/api-client/server';
import { cookies } from 'next/headers';
import { authCookies } from './auth-cookies';
import { gatewayUrl } from './env';

/** Server Component 용. 재발급하지 않는다 — 401 이면 ApiError(unauthorized) 를 던진다. */
export async function serverFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const store = await cookies();
  return gatewayRequest<T>(path, {
    gatewayUrl,
    accessToken: store.get(authCookies.accessName)?.value ?? null,
    init,
  });
}
```

- [ ] **Step 3: route handler**

`frontend/apps/admin/src/app/api/[...path]/route.ts`:

```ts
import { createProxyHandler } from '@cf/api-client/server';
import { authCookies } from '@/lib/auth-cookies';
import { gatewayUrl } from '@/lib/env';

const handler = createProxyHandler({ gatewayUrl, cookies: authCookies });

export const dynamic = 'force-dynamic';
export { handler as DELETE, handler as GET, handler as PATCH, handler as POST, handler as PUT };
```

`frontend/apps/admin/src/app/api/health/route.ts`:

```ts
import { healthResponse } from '@cf/api-client/server';
import { gatewayUrl } from '@/lib/env';

export const dynamic = 'force-dynamic';

export function GET(): Promise<Response> {
  return healthResponse(gatewayUrl);
}
```

- [ ] **Step 4: 레이아웃·페이지·스타일**

`frontend/apps/admin/src/app/globals.css`:

```css
@import 'tailwindcss';
@import '@cf/ui/tokens.css';
/* 어드민은 브랜드 테마를 쓰지 않는다 (중립 토큰) */

@source '../../../../packages/ui/src';
```

`frontend/apps/admin/src/app/layout.tsx`:

```tsx
import { brand } from '@cf/brand';
import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import './globals.css';

export const metadata: Metadata = {
  title: { default: `${brand.name} 어드민`, template: `%s | ${brand.name} 어드민` },
  robots: { index: false, follow: false },
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="ko">
      <body className="bg-bg font-sans text-fg antialiased">{children}</body>
    </html>
  );
}
```

`frontend/apps/admin/src/app/page.tsx`:

```tsx
import { checkGateway } from '@cf/api-client/server';
import { brand } from '@cf/brand';
import { gatewayUrl } from '@/lib/env';

export const dynamic = 'force-dynamic';

// 임시 화면 — #29 에서 어드민 로그인·대시보드로 바꾼다.
export default async function AdminHomePage() {
  const gateway = await checkGateway(gatewayUrl);

  return (
    <main className="mx-auto flex min-h-dvh max-w-xl flex-col justify-center gap-4 px-4">
      <h1 className="text-2xl font-semibold">{brand.name} 어드민</h1>
      <p className="text-muted-fg">어드민 콘솔 초기 구성</p>
      <p>
        게이트웨이:{' '}
        <span className={gateway === 'UP' ? 'font-medium text-primary' : 'font-medium text-danger'}>
          {gateway}
        </span>
      </p>
    </main>
  );
}
```

- [ ] **Step 5: 검사·빌드·dev 확인**

Run: `pnpm typecheck && pnpm lint && pnpm format:check && pnpm build`
Expected: 두 앱 모두 성공

Run (백그라운드): `pnpm dev` (turbo 가 두 앱을 같이 띄운다)
Then: `curl -s -o /dev/null -w '%{http_code}\n' localhost:3001/` → `200`, `curl -s localhost:3001/api/health` → `{"app":"UP","gateway":"DOWN"}`
dev 서버를 종료한다.

- [ ] **Step 6: Commit**

```bash
git add frontend/apps/admin/package.json frontend/apps/admin/tsconfig.json \
  frontend/apps/admin/next.config.ts frontend/apps/admin/postcss.config.mjs \
  frontend/apps/admin/src frontend/pnpm-lock.yaml
git commit -m "[FE] feat: 어드민 앱에 BFF 프록시·헬스체크·중립 테마를 연결한다 (#23)"
```

---

### Task 7: frontend-ci

**Files:**
- Create: `.github/workflows/frontend-ci.yml`, `.github/scripts/frontend-test-count.mjs`

**Interfaces:**
- Consumes: 루트 스크립트 `format:check` · `lint` · `typecheck` · `test:ci` · `build` (Task 1), 리포트 경로 `frontend/test-results/vitest.json`

- [ ] **Step 1: `.github/scripts/frontend-test-count.mjs`**

```js
// Vitest JSON 리포트에서 패키지별 통과 테스트 수를 세고,
// 테스트가 있어야 하는 패키지가 0 건이면 실패한다 ("초록불인데 테스트 0건" 방지 — backend-ci 와 같은 취지).
// 사용: node ../.github/scripts/frontend-test-count.mjs test-results/vitest.json  (frontend/ 에서 실행)
import { readFileSync } from 'node:fs';
import path from 'node:path';

const REQUIRED = ['packages/api-client/', 'packages/ui/'];

const reportPath = process.argv[2];
if (!reportPath) {
  console.error('usage: frontend-test-count.mjs <vitest-json-report>');
  process.exit(2);
}

const report = JSON.parse(readFileSync(reportPath, 'utf8'));
const counts = new Map(REQUIRED.map((prefix) => [prefix, 0]));

for (const file of report.testResults ?? []) {
  const relative = path.relative(process.cwd(), file.name).split(path.sep).join('/');
  const passed = (file.assertionResults ?? []).filter((a) => a.status === 'passed').length;
  for (const prefix of REQUIRED) {
    if (relative.startsWith(prefix)) counts.set(prefix, counts.get(prefix) + passed);
  }
}

let failed = false;
for (const [prefix, count] of counts) {
  console.log(`${prefix.padEnd(24)} ${count} passed`);
  if (count === 0) failed = true;
}
console.log(`total ${report.numPassedTests}/${report.numTotalTests}`);

if (failed) {
  console.error('테스트가 있어야 하는 패키지에서 통과한 테스트가 0 건이다.');
  process.exit(1);
}
```

- [ ] **Step 2: 로컬에서 스크립트 확인**

Run (`frontend/` 에서): `pnpm test:ci && node ../.github/scripts/frontend-test-count.mjs test-results/vitest.json`
Expected: `packages/api-client/` 와 `packages/ui/` 가 각각 0 보다 큰 수로 출력되고 종료 코드 0.

음성 확인: `pnpm vitest run packages/api-client --reporter=json --outputFile.json=test-results/only-api.json && node ../.github/scripts/frontend-test-count.mjs test-results/only-api.json; echo "exit=$?"`
Expected: `packages/ui/ 0 passed`, `exit=1`. 확인 후 `rm test-results/only-api.json`.

- [ ] **Step 3: `.github/workflows/frontend-ci.yml`**

```yaml
name: frontend-ci

# **프론트엔드 PR 에서 format · lint · 타입 · 테스트 · 빌드를 돌리고, 테스트가 실제로 몇 건 돌았는지 센다.**
# backend-ci 와 같은 이유로 "초록불인데 테스트가 한 건도 안 돌았다" 를 막는다.

on:
  pull_request:
    paths:
      - 'frontend/**'
      - '.github/workflows/frontend-ci.yml'
      - '.github/scripts/frontend-test-count.mjs'
  # develop 에서도 돈다. Actions 캐시는 기본 브랜치 스코프만 모든 PR 이 상속한다.
  push:
    branches:
      - develop
    paths:
      - 'frontend/**'
      - '.github/workflows/frontend-ci.yml'
      - '.github/scripts/frontend-test-count.mjs'

# 같은 PR 에 새 커밋이 올라오면 이전 실행을 버린다.
concurrency:
  group: frontend-ci-${{ github.ref }}
  cancel-in-progress: true

permissions:
  contents: read

jobs:
  check:
    runs-on: ubuntu-latest
    timeout-minutes: 20
    defaults:
      run:
        working-directory: frontend
    steps:
      - uses: actions/checkout@v4

      # pnpm 버전은 frontend/package.json 의 packageManager 를 따른다.
      - uses: pnpm/action-setup@v4
        with:
          package_json_file: frontend/package.json

      - uses: actions/setup-node@v4
        with:
          node-version-file: frontend/.nvmrc
          cache: pnpm
          cache-dependency-path: frontend/pnpm-lock.yaml

      - name: Install
        run: pnpm install --frozen-lockfile

      - name: Format
        run: pnpm format:check

      - name: Lint
        run: pnpm lint

      - name: Typecheck
        run: pnpm typecheck

      - name: Test
        run: pnpm test:ci

      - name: Test count
        run: node ../.github/scripts/frontend-test-count.mjs test-results/vitest.json

      - name: Build
        run: pnpm build
```

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/frontend-ci.yml .github/scripts/frontend-test-count.mjs
git commit -m "[INFRA] chore: frontend-ci 를 추가하고 패키지별 테스트 실행 건수를 센다 (#23)"
```

---

### Task 8: 규칙 문서·엔트리·Claude 에이전트

**Files:**
- Create: `frontend/CLAUDE.md`, `frontend/docs/architecture.md`, `frontend/docs/api-client.md`, `frontend/docs/styling-theme.md`, `frontend/docs/local-run.md`, `frontend/docs/conventions.md`
- Create: `.claude/agents/fe-implementer.md`, `.claude/agents/fe-reviewer.md`
- Modify: `CLAUDE.md` (워크스페이스 표 `frontend/` 행, 스킬/에이전트 절 마지막 줄), `docs/claude-agents.md` (`### 프론트엔드` 절)
- Modify: `frontend/docs/specs/2026-10-15-frontend-bootstrap-design.md` §7 (lint·test 를 turbo 가 아니라 루트에서 실행)

- [ ] **Step 1: `frontend/CLAUDE.md`**

```markdown
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
| [docs/conventions.md](docs/conventions.md) | 파일·네이밍·import·테스트 규칙 |

## 핵심 규칙

- 브라우저에서 게이트웨이를 직접 부르지 않는다. 클라이언트는 `apiFetch('/api/v1/...')`, 서버 컴포넌트는 `serverFetch`.
- 토큰은 httpOnly 쿠키에만 있다. JS 에서 토큰을 읽거나 localStorage 에 두지 않는다.
- 금액은 서버 값을 `formatMoney` 로 표시만 한다. 클라이언트에서 합계·할인을 계산하지 않는다.
- 색은 토큰 클래스(`bg-primary` 등)로만 쓴다. 앱 코드에 브랜드 이름을 하드코딩하지 않는다.
- 완료 보고 전에 위 검사 명령을 실제로 돌린다.

## 에이전트

`fe-implementer`(구현), `fe-reviewer`(읽기 전용 검토). 정본은 루트 `docs/claude-agents.md`.
```

- [ ] **Step 2: `frontend/docs/architecture.md`**

```markdown
# Frontend Architecture

## 1. 모노레포

pnpm workspace + Turborepo. 앱 둘과 패키지 셋이다.

| 패키지 | 의존해도 되는 것 | 의존하면 안 되는 것 |
|--------|------------------|---------------------|
| `@cf/api-client` | 표준 Web API(`fetch`, `Request`, `Response`) | React, Next, 다른 `@cf/*` |
| `@cf/ui` | React | Next, `@cf/api-client`, 브랜드 |
| `@cf/theme-<brand>` | `@cf/ui`(타입) | 앱 |
| `apps/*` | 위 전부 | 다른 앱 |

- 패키지는 빌드하지 않는다. TS 소스를 `exports` 로 내보내고 앱이 `transpilePackages` 로 소비한다.
- 공유 의존성 버전은 `pnpm-workspace.yaml` 의 `catalog:` 에만 적는다.
- lint·format·test 는 루트에서 한 번에, typecheck·build·dev 는 turbo 로 패키지별로 돈다.

## 2. 요청 흐름 (BFF)

```text
[Client Component] apiFetch('/api/v1/...')        같은 origin
      ↓
[apps/*/src/app/api/[...path]/route.ts]            createProxyHandler (@cf/api-client/server)
  - /api/v1/** 만 전달, 그 외 BFF_001 404
  - Content-Type · Accept · Accept-Language · Idempotency-Key 만 전달
  - access 쿠키 → Authorization: Bearer (클라이언트가 보낸 Authorization 은 버린다)
  - 401 + refresh 쿠키 → 재발급 1회 → 새 쿠키 → 원 요청 1회 재시도. 재발급 실패면 쿠키 삭제 + 401
  - 게이트웨이 Set-Cookie 는 버린다. 연결 실패는 BFF_002 503
      ↓
[API Gateway :8000] → 서비스

[Server Component] serverFetch(...)                게이트웨이 직접 호출. 재발급하지 않는다(401 → 로그인으로)
```

- 브라우저는 게이트웨이를 직접 부르지 않으므로 운영 CORS 가 필요 없다.
- 401 은 인증 단계 거절이라 서버가 처리하지 않았다. 쓰기 요청도 재발급 후 1회 재시도가 안전하다.
- 같은 refresh 토큰의 동시 재발급은 인스턴스 안에서 한 번으로 합친다. 다중 인스턴스 경합은 #25 에서 BE 와 정한다.
- 재발급이 성공한 뒤 재시도가 연결 실패해도 새 쿠키는 심는다(회전된 refresh 토큰을 잃지 않는다).

## 3. 쿠키

- `HttpOnly; SameSite=Lax; Path=/`, 운영은 `Secure`.
- 고객 `cf_at` / `cf_rt`, 어드민 `cf_admin_at` / `cf_admin_rt` — 세션을 섞지 않는다.
- 이름·수명은 임시다. 고객은 #25, 어드민은 #29 에서 BE 토큰 만료와 맞춰 확정한다.
- 재발급 요청·응답 모양(`{ refreshToken }` → `{ accessToken, refreshToken }`)은 #4 기준 가정이다. #4 머지 후 `packages/api-client/src/server/reissue.ts` 를 맞춘다.

## 4. 헬스체크

각 앱 `GET /api/health` → 게이트웨이 `/actuator/health` 결과로 `{ app, gateway }`. 게이트웨이 DOWN 이면 503.
```

- [ ] **Step 3: `frontend/docs/api-client.md`**

```markdown
# API Client Guide

백엔드 계약 정본은 `backend/docs/api-design-guide.md` §2 · §4 · §6 이다. 이 문서는 프론트에서 그것을 다루는 법이다.

## 1. 호출

| 어디서 | 무엇으로 |
|--------|----------|
| Client Component | `apiFetch<T>('/api/v1/...', { method, json, idempotencyKey })` (`@cf/api-client`) |
| Server Component | `serverFetch<T>('/api/v1/...')` (앱의 `src/lib/server-api.ts`) |
| route handler | `@cf/api-client/server` 의 함수 |

`@cf/api-client/server` 는 클라이언트 컴포넌트에서 import 하지 않는다.

## 2. 성공

- `dataBody` 를 그대로 돌려준다. 선택적 하위 리소스 부재는 `null` 이 정상이다.
- `204 No Content` 는 `null` 이다.
- 목록 타입: 스토어프론트 `SliceResponse<T>`, 어드민 `PageResponse<T>`(page 는 0 부터, 화면은 +1).

## 3. 실패 — `ApiError`

`kind` · `status` · `code`(resultCode) · `message` · `fieldErrors` 를 갖는다. 판정은 위에서부터 먼저 맞는 것이다.

| kind | 조건 | 화면 기본 처리 |
|------|------|----------------|
| `network` | fetch 자체 실패 | 일시 오류 안내 |
| `invalidResponse` | 봉투가 아닌 응답 | 일시 오류 안내 |
| `validation` | `fieldErrors` 가 비어 있지 않다 | `messagesFor(error, field)` 로 필드별 표시 |
| `unauthorized` | 401 (BFF 재발급까지 실패) | 로그인 이동 |
| `forbidden` | 403 | 권한 없음 안내 |
| `notFound` | 404 | not-found |
| `conflict` | 409 (같은 멱등 키 처리 중) | 잠시 후 결과 조회 |
| `unknownOutcome` | 502 · 504 | **쓰기는 자동 재시도 금지.** 같은 키로 재시도하거나 조회 |
| `unavailable` | 503 | 잠시 후 재시도 안내 |
| `server` | 그 밖 | `message` 표시 |

- `message` 는 서버 `resultMessage` 가 있으면 그것, 없으면 kind 별 기본 문구다.
- 요청 취소(`AbortError`)는 `ApiError` 로 바뀌지 않고 그대로 던져진다. 오류 안내를 띄우지 않는다.
- BFF 자체 코드: `BFF_001`(404, `/api/v1` 밖 경로), `BFF_002`(503, 게이트웨이 연결 실패).

## 4. 멱등 키

- 주문 생성·결제 승인·취소·환불은 `idempotencyKey` 를 보낸다.
- `createIdempotencyKey()` 로 "사용자 의도 한 번"(버튼 한 번)에 하나 만들고, 그 의도의 재시도에는 같은 키를 쓴다.
- 버튼은 요청 중 비활성화해 더블 클릭을 막는다.

## 5. 금액

- `Money = { amount, currency }`, amount 는 최소 화폐 단위 정수(KRW 원, USD 센트).
- 표시는 `formatMoney(money)` 만 쓴다: KRW `129,000원`, USD `$89.50`.
- 클라이언트에서 금액을 계산하지 않는다. 합계·할인·배송비는 서버 값을 표시한다.
```

- [ ] **Step 4: `frontend/docs/styling-theme.md`**

```markdown
# Styling & Theme Guide

## 1. 토큰 두 층

| 층 | 파일 | 하는 일 |
|----|------|---------|
| 중립 토큰 | `packages/ui/src/tokens.css` | `--cf-*` 원시 토큰 기본값 + Tailwind `@theme inline` 연결 |
| 브랜드 테마 | `packages/theme-<brand>/theme.css` | `--cf-*` 값만 덮어쓴다 |

사용 가능한 클래스: `bg-bg` `text-fg` `bg-muted` `text-muted-fg` `border-border` `bg-primary` `text-primary-fg` `text-danger` `border-danger` `ring-primary` `rounded-md` `font-sans`.
새 토큰이 필요하면 `tokens.css` 에 `--cf-*` 와 `@theme` 연결을 같이 더하고 이 목록을 갱신한다.

## 2. 규칙

- 임의 색 값(`bg-[#fff]`, `text-[rgb(...)]`)을 쓰지 않는다. ESLint `no-restricted-syntax` 가 막는다.
- 앱 코드에 브랜드 이름·색을 하드코딩하지 않는다. 이름은 `brand.name`(`@cf/brand`).
- 어드민은 브랜드 테마 CSS 를 import 하지 않는다(중립 토큰).
- 폰트: 테마가 `--cf-font-sans` 를 정한다. 웹폰트를 로딩해야 하면 앱 `layout.tsx` 에서 `next/font` 로 하고 변수를 연결한다.
- 다크 모드는 아직 없다.

## 3. 고객사 정보

사업자등록번호·대표자 등 실정보는 테마에 넣지 않는다(공개 저장소). 서버 env(`BUSINESS_*`)로 받는다.

## 4. 새 브랜드 추가

1. `packages/theme-mokoyuri` 를 복사해 `packages/theme-<brand>` 를 만들고 이름·`theme.css` 값·`brand.ts` 를 바꾼다.
2. 앱 `package.json` 의 `"@cf/brand": "workspace:@cf/theme-<brand>@*"` 한 줄을 바꾼다.
3. `pnpm install && pnpm build`.

## 5. 현재 MOKOYURI 팔레트

임시 값이다(한지 아이보리 배경 · 쪽빛 primary). 브랜드 가이드를 받으면 `theme.css` 값만 바꾼다.
```

- [ ] **Step 5: `frontend/docs/local-run.md`**

```markdown
# Frontend Local Run Guide

## 1. 준비

- Node 24 (`frontend/.nvmrc`): `nvm use` (없으면 `nvm install`)
- pnpm 10.30.3: `corepack enable` 이면 `packageManager` 버전을 자동으로 쓴다

```bash
cd frontend
pnpm install
```

## 2. 환경변수

키 목록은 `frontend/.env.example`. 기본값으로 로컬에서 그대로 뜬다. 바꿀 때만 앱 디렉터리에 `.env.local` 을 만든다
(예: `apps/storefront/.env.local` 에 `GATEWAY_URL=http://localhost:8000`). `.env.local` 은 커밋되지 않는다.

## 3. 실행

```bash
pnpm dev                              # storefront :3000 + admin :3001
pnpm --filter @cf/storefront dev      # 하나만
```

| 주소 | 기대 |
|------|------|
| `http://localhost:3000` | 브랜드명과 게이트웨이 상태 |
| `http://localhost:3000/api/health` | `{"app":"UP","gateway":"UP"}` (게이트웨이가 없으면 503 + `DOWN`) |
| `http://localhost:3001/api/health` | 같음 |

## 4. 게이트웨이와 함께

게이트웨이는 DB · Redis 없이 뜬다 (`backend/docs/local-run-guide.md`).

```bash
cd backend
./gradlew :cloud:service-discovery:bootRun   # 8761
./gradlew :cloud:api-gateway:bootRun         # 8000
```

BFF 구조라 브라우저가 게이트웨이를 직접 부르지 않는다. 게이트웨이 CORS 설정은 로컬 개발에 영향이 없다.

## 5. 검사

```bash
pnpm format:check && pnpm lint && pnpm typecheck && pnpm test && pnpm build
```
```

- [ ] **Step 6: `frontend/docs/conventions.md`**

```markdown
# Frontend Conventions

## 1. 파일·이름

- 컴포넌트 파일은 `PascalCase.tsx`, 그 외는 `kebab-case.ts`.
- 앱 내부 import 는 `@/` 별칭(`src/`), 패키지는 `@cf/*` 이름으로 import 한다. 패키지 사이에 상대 경로로 넘나들지 않는다.
- 패키지 공개 API 는 `src/index.ts`(서버 전용은 `src/server/index.ts`)로만 내보낸다.

## 2. 코드

- TypeScript strict + `noUncheckedIndexedAccess`. `any` 대신 `unknown` 과 좁히기.
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
- ESLint: `eslint-config-next`(core-web-vitals + typescript) + 임의 색 값 금지.
```

- [ ] **Step 7: 에이전트 정의**

`.claude/agents/fe-implementer.md`:

```markdown
---
name: fe-implementer
description: commerce-foundry 프론트엔드(frontend/, Next.js App Router 모노레포)의 실제 구현 작업에 사용한다. 이슈 명세나 계획이 이미 있는 상태에서 앱 화면·route handler·packages(api-client, ui, theme) 를 작성/수정하고 format·lint·typecheck·test·build 까지 통과시키는 워크호스 에이전트다. 설계가 미정이면 먼저 architect 를 쓴다.
model: opus
---

너는 commerce-foundry 프론트엔드 **구현 담당**이다.

## 먼저 읽는다

- `frontend/CLAUDE.md`, 작업과 관련된 `frontend/docs/*.md`
- API 를 쓰면 `backend/docs/api-design-guide.md` 의 해당 절

## 지킨다

- 브라우저에서 게이트웨이를 직접 부르지 않는다. 클라이언트는 `apiFetch`, 서버 컴포넌트는 `serverFetch`.
- 토큰을 JS 에서 다루지 않는다(httpOnly 쿠키). 금액은 `formatMoney` 로 표시만 한다.
- 색은 토큰 클래스만. 브랜드 이름·고객사 실정보를 코드에 쓰지 않는다(공개 저장소).
- 새 분기에는 테스트를 같이 쓴다(`frontend/docs/conventions.md` §3).

## 끝내기 전에 (frontend/ 에서)

```bash
pnpm format:check && pnpm lint && pnpm typecheck && pnpm test && pnpm build
```

돌리지 않은 검사를 통과했다고 보고하지 않는다. 실패하면 출력과 함께 보고한다.

## 하지 않는다

커밋·푸시·이슈·PR 생성, 하위 에이전트 생성. 그 일은 메인 세션의 몫이다.
```

`.claude/agents/fe-reviewer.md`:

```markdown
---
name: fe-reviewer
description: commerce-foundry 프론트엔드(frontend/) 변경 diff 를 frontend/docs 규칙 기준으로 검토할 때 사용한다. 커밋·PR 직전, fe-implementer 가 구현을 끝냈을 때가 트리거다. BFF·쿠키 경계, 봉투·ApiError 처리, 금액 표시, 멱등 키, 테마 토큰 규칙을 본다. 읽기 전용이며 코드를 수정하지 않는다.
tools: Read, Grep, Glob, Bash
model: opus
---

너는 commerce-foundry 프론트엔드 **리뷰어**다. 코드를 고치지 않는다. 발견사항만 보고한다.

## 검토 대상 확보

```bash
git diff --stat origin/develop...HEAD -- frontend .github/workflows/frontend-ci.yml
git diff origin/develop...HEAD -- frontend
```

## 보는 것

1. **인증 경계** — 브라우저가 게이트웨이를 직접 부르는가, 토큰을 JS·localStorage 에서 다루는가, `@cf/api-client/server`·`next/headers` 를 클라이언트 컴포넌트에서 import 하는가
2. **오류 처리** — `ApiError.kind` 별 처리가 `frontend/docs/api-client.md` §3 과 맞는가. `unknownOutcome` 쓰기 요청을 자동 재시도하는가
3. **멱등성** — 주문·결제 쓰기에 `idempotencyKey` 가 있고 재시도에 같은 키를 쓰는가, 더블 클릭을 막는가
4. **금액** — 클라이언트에서 금액을 계산하는가, `formatMoney` 를 거치지 않고 표시하는가
5. **테마** — 임의 색 값, 브랜드 이름 하드코딩, 어드민의 브랜드 테마 import
6. **공개 저장소** — 키·토큰·고객사 실정보·개인정보
7. **테스트** — 새 분기에 테스트가 있고 실제로 그 분기를 지나가는가

## 보고 형식

심각도(높음/중간/낮음), `파일:줄`, 문제, 근거(규칙 문서 절), 제안. 문제가 없으면 없다고 쓴다.
```

- [ ] **Step 8: 루트 `CLAUDE.md` 와 `docs/claude-agents.md` 갱신**

`CLAUDE.md` 의 워크스페이스 표 행:

```diff
-| `frontend/` | Next.js (스토어프론트 · 어드민) | `seonghoho` | `frontend/CLAUDE.md` (프론트에서 작성) | `frontend/docs/*.md` |
+| `frontend/` | Next.js 모노레포 (스토어프론트 · 어드민) | `seonghoho` | `frontend/CLAUDE.md` | `frontend/docs/*.md` |
```

`CLAUDE.md` 의 에이전트 표에 행 추가, 마지막 줄 교체:

```diff
 | 백엔드 | `be-executor`, `be-hexagonal-reviewer`, `be-db-reviewer`, `be-security-reviewer`, `be-payment-reviewer` |
+| 프론트엔드 | `fe-implementer`, `fe-reviewer` |
```

```diff
-- 프론트엔드 스킬·에이전트는 프론트 담당자가 `frontend/` 구성 시 추가한다 (`fe-*` 접두어).
+- 프론트엔드 에이전트는 `fe-*` 접두어다. 프론트 전용 스킬은 반복 작업이 생기면 `fe-*` 로 추가한다.
```

`docs/claude-agents.md` 의 `### 프론트엔드` 절 본문을 교체:

```markdown
### 프론트엔드

| 역할 | 모델 | 권한 | 사용 시점 |
|------|------|------|-----------|
| `fe-implementer` | Opus | 쓰기 | 앱 화면·route handler·packages 실구현, format·lint·typecheck·test·build 통과까지 |
| `fe-reviewer` | Opus | 읽기 전용 | BFF·쿠키 경계, ApiError 처리, 금액·멱등 키, 테마 토큰 규칙 검토 |
```

(`fe-implementer` 는 `be-executor` 와 같이 `tools` 를 생략해 전체 도구를 상속하고 `model: opus` 를 둔다.)

- [ ] **Step 9: 설계 문서 §7 동기화**

`frontend/docs/specs/2026-10-15-frontend-bootstrap-design.md` §7 의 단계 줄을 바꾼다:

```diff
-- `pnpm/action-setup` → `actions/setup-node`(`.nvmrc`, pnpm 캐시) → `pnpm install --frozen-lockfile`
-  → `pnpm turbo run lint typecheck test build`
+- `pnpm/action-setup` → `actions/setup-node`(`.nvmrc`, pnpm 캐시) → `pnpm install --frozen-lockfile`
+  → `format:check` → `lint` → `typecheck` → `test:ci` → 테스트 건수 검사 → `build`
+- lint · format · test 는 루트에서 한 번에 돈다(설정 하나로 전 패키지). typecheck · build 만 turbo 로 패키지별로 돈다.
```

- [ ] **Step 10: Commit (문서와 Claude 설정을 나눈다)**

```bash
git add frontend/CLAUDE.md frontend/docs/architecture.md frontend/docs/api-client.md \
  frontend/docs/styling-theme.md frontend/docs/local-run.md frontend/docs/conventions.md \
  frontend/docs/specs/2026-10-15-frontend-bootstrap-design.md
git commit -m "[DOCS] docs: 프론트엔드 엔트리와 구조·API 클라이언트·테마·로컬 실행·컨벤션 규칙을 적는다 (#23)"

git add .claude/agents/fe-implementer.md .claude/agents/fe-reviewer.md CLAUDE.md docs/claude-agents.md
git commit -m "[INFRA] chore: fe-implementer·fe-reviewer 에이전트를 추가하고 엔트리 문서에 등록한다 (#23)"
```

---

### Task 9: 완료 검증과 PR

**Files:** 없음 (검증·PR)

- [ ] **Step 1: 깨끗한 설치로 전체 검사**

Run (`frontend/`):

```bash
rm -rf node_modules apps/*/node_modules packages/*/node_modules apps/*/.next .turbo
pnpm install --frozen-lockfile
pnpm format:check && pnpm lint && pnpm typecheck && pnpm test:ci \
  && node ../.github/scripts/frontend-test-count.mjs test-results/vitest.json && pnpm build
```

Expected: 전부 성공. 테스트 건수 출력에서 api-client · ui 모두 0 보다 큼.

- [ ] **Step 2: 게이트웨이 연동 확인 (이슈 완료 기준)**

Run (각각 백그라운드, `backend/`): `./gradlew :cloud:service-discovery:bootRun`, `./gradlew :cloud:api-gateway:bootRun` → `curl -s localhost:8000/actuator/health` 가 `"status":"UP"` 이 될 때까지 대기
Run (백그라운드, `frontend/`): `pnpm dev`
Then:
- `curl -s -w ' %{http_code}\n' localhost:3000/api/health` → `{"app":"UP","gateway":"UP"} 200`
- `curl -s -w ' %{http_code}\n' localhost:3001/api/health` → `{"app":"UP","gateway":"UP"} 200`
- `curl -s -w ' %{http_code}\n' localhost:3000/api/v1/products` → 게이트웨이 봉투(서비스 미기동이면 `GATEWAY_002` 503 또는 `GATEWAY_001` 404) — **BFF_002 가 아니어야** 게이트웨이까지 도달한 것이다
게이트웨이를 끈 뒤: `curl -s -w ' %{http_code}\n' localhost:3000/api/health` → `{"app":"UP","gateway":"DOWN"} 503`
모든 프로세스를 종료한다.

- [ ] **Step 3: 최종 리뷰**

`fe-reviewer` 와 `reviewer` 에이전트로 `origin/develop...HEAD` 를 검토하고, 지적을 반영한 뒤 Step 1 을 다시 돌린다.

- [ ] **Step 4: PR**

`/pr` 스킬로 develop 대상 PR 을 만든다. 본문에 적는다:
- 30파일 초과 사유: 초기 골격(설정 · 앱 2 · 패키지 3 · lockfile · 문서 · CI)이 서로 맞물려 따로 동작 확인이 어렵다. 커밋을 단위별로 나눴으니 커밋 단위로 리뷰할 수 있다.
- BE 알림(`shindonggeun`): BFF 구조라 토큰은 응답 본문으로 주면 된다(쿠키·CORS 불필요). 재발급 요청·응답 가정(`{refreshToken}` → `{accessToken, refreshToken}`)과 refresh 회전 시 동시 재발급 경합은 #25 에서 맞춘다.
- 검증 결과(Step 1 · 2 출력 요약).
