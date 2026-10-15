# Frontend Local Run Guide

## 1. 준비

- Node 24 (`frontend/.nvmrc`): `nvm use` (없으면 `nvm install`)
- pnpm 10.30.3: `corepack enable` 이면 `packageManager` 버전을 자동으로 쓴다

```bash
cd frontend
pnpm install
```

`Ignored build scripts: unrs-resolver` 경고는 무시해도 된다(ESLint import resolver 의 선택적 네이티브 빌드다).

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
