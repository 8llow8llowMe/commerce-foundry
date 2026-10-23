# Secret Management

> 저장소가 public 이라 이 문서의 규칙은 예외 없이 지킨다.
> 키 목록의 정본은 [`backend/.env.example`](../.env.example) 이다.

## 1. 원칙

- 실제 값은 **Vault 에만** 둔다. 저장소에는 키 **이름**만 둔다.
- **Vault 키 이름 = 환경변수 이름 = `.env.example` 변수명.** 이름이 셋으로 갈라지지 않게 한다.
- `application*.yml` 은 `${TOSS_SECRET_KEY}` 같은 플레이스홀더만 쓴다. 기본값(`${KEY:기본값}`)에도 실값을 넣지 않는다.
- Jasypt 처럼 **저장소 안에 암호문을 두는 방식은 쓰지 않는다.** 복호화 키가 새면 히스토리 전체가 같이 새고,
  값이 두 곳(Vault·yml)에 생겨 어느 쪽이 정본인지 흐려진다.

## 2. 무엇을 어디에 두는가

| 구분 | 예 | 위치 |
|------|----|------|
| 시크릿 | PG 시크릿 키, JWT 개인키(auth 전용), 서비스 토큰 비밀(`INTERNAL_JWT_SECRET` · `_PREVIOUS` — 서비스 5종 공통, 게이트웨이 제외), 클라이언트 IP HMAC 키(`GATEWAY_CLIENT_IP_HASH_KEY` — 게이트웨이 전용, 인스턴스 공통, `gateway.client-ip.enabled` 를 켤 때만 필요), 로그인 · 가입 시도 이메일 HMAC 키(`AUTH_LOGIN_GUARD_HASH_KEY` — auth 전용, 인스턴스 공통, `auth.login-guard.enabled` 를 켤 때만 필요), DB·Redis 비밀번호, 소셜 client secret, 스토리지 키, 초기 관리자 시드(`ADMIN_SEED_*` — auth 전용) | Vault |
| 배포용 공개값 | JWT 공개키(전 서비스 · 게이트웨이). 비밀은 아니지만 교체를 한 곳에서 하려고 Vault 로 배포한다 | Vault |
| 고객사 실정보 | 상호, 대표자, 사업자등록번호, 통신판매업 신고번호, 사업장 주소, CS 연락처 | Vault |
| 일반 설정 | 포트, 타임아웃, `store.features.*`, 로케일, 통화 | `application*.yml` |
| 프론트 공개 키 | 토스 클라이언트 키 | 프론트 환경변수 (`frontend/` 담당) |

- 사업자 정보는 화면에 공개되는 값이지만 **고객사마다 다르고 실정보**라서 시크릿과 같이 다룬다.
  템플릿 저장소에 MOKOYURI 의 값이 박히면 다른 고객사 배포에 그대로 따라간다.
- 정산 계좌는 우리 시스템이 다루지 않는다 (PG 상점관리자에서 고객사가 관리).
- `store.features.*` 같은 고객사별 기능 플래그는 비밀이 아니다 — 기본값은 yml, 고객사별 값은 환경변수(배포 설정, Vault 등록 불필요)로 넣는다.
  `.env.example` 에 이름이 있지만 Vault 키는 아니다.
- 오브젝트 스토리지(#9, `storage.*`)는 `STORAGE_ENDPOINT` · `STORAGE_REGION` · `STORAGE_BUCKET` · `STORAGE_ACCESS_KEY` · `STORAGE_SECRET_KEY` ·
  `STORAGE_PUBLIC_BASE_URL` 여섯을 Vault 에 둔다. 비밀은 두 키뿐이지만 제공자 · 계정이 드러나는 값이라 같이 둔다.
  `STORAGE_ENABLED` 는 비밀이 아니라 설정 스위치다 — `store.features.*` 처럼 배포 설정의 환경변수로 넣고 Vault 에 등록하지 않는다.
  기본값은 꺼짐이고, 켜면 여섯 값이 다 있어야 기동한다 (`bootstrap-conventions.md` Storage).
- JWT 키 만들기 · 환경변수 한 줄 변환 · 교체 순서는 [security-guide.md](security-guide.md) §6 이 정본이다.
- 서비스 토큰 비밀(`INTERNAL_JWT_SECRET`)은 `openssl rand -base64 32` 로 만들고 서비스 5종에 **같은 값**을 넣는다. 게이트웨이에는 넣지 않는다.
  교체는 `INTERNAL_JWT_SECRET_PREVIOUS` 로 3단계를 밟는다 — 정본은 [security-guide.md](security-guide.md) §9-6 · §9-7.
- **클라이언트 IP HMAC 키(`GATEWAY_CLIENT_IP_HASH_KEY`, 게이트웨이 전용, #89 · #135)는 Vault 등록 필요(dev/prod)다.** `openssl rand -base64 32` 로 만들고
  게이트웨이 인스턴스 전부에 같은 값을 넣는다. 비로그인 버킷 키와 하류 `X-Client-Subject` 값(security-guide.md §7-3)을 만든다 — 서비스에는 넣지 않는다.
  스위치 `GATEWAY_CLIENT_IP_ENABLED` 와 `GATEWAY_TRUSTED_PROXIES`(BFF · LB 송신 주소)는 비밀이 아니라 배포 설정의 환경변수다 — Vault 에 등록하지 않는다.
- **초기 관리자 시드(`ADMIN_SEED_EMAIL` · `ADMIN_SEED_PASSWORD` · `ADMIN_SEED_NAME`, auth 전용, #6)는 Vault 등록 필요(dev/prod)다.**
  실제 관리자 이메일 · 비밀번호를 yml · 테스트 픽스처 · 이슈 · PR 에 쓰지 않는다 — yml 은 `${ADMIN_SEED_EMAIL:}` 플레이스홀더만 둔다.
  비밀번호는 12~64자, 영문 · 숫자 포함 공백 없는 ASCII 이고(`openssl rand -base64 24` 로 만들고 영문 · 숫자가 모두 들었는지 본다), 같은 이메일의 관리자가 이미 있으면 덮어쓰지 않으므로
  첫 배포로 관리자를 만든 뒤 Vault 값을 지워도 된다. 비밀번호 교체 API 는 아직 없다 — 바꾸려면 후속 이슈를 쓴다. 규칙 정본은 [security-guide.md](security-guide.md) §11-3.

## 3. 환경별 값의 출처

| 환경 | 출처 | 토스 키 |
|------|------|---------|
| local | `backend/.env` — `.env.example` 을 복사해 채운다 (커밋 금지) | `test_sk_` 만 |
| dev | Vault | `test_sk_` 만 |
| prod | Vault | `live_sk_` 만 |

- 주입 방식(Spring Cloud Vault / Vault Agent → 환경변수)과 Vault 경로·인증 방식은 #32 에서 확정하고 이 절을 갱신한다.
- **개발자 계정은 테스트 키만 다룬다.** 라이브 키는 고객사 계정 소유이고 Vault 에만 있다.
- 키가 뒤바뀐 채로 기동하면 실패시킨다 (`payment-guide.md` §2).

## 4. 키를 추가·변경할 때

1. `backend/.env.example` 에 이름과 설명 주석을 추가한다. **값은 비운다** (로컬 기본 호스트·포트처럼 비밀이 아닌 값만 예외).
2. 쓰는 서비스의 `application*.yml` 에 플레이스홀더를 추가한다.
   같은 PR 에서 그 모듈의 배포 compose(`docker-compose-<module>.yml`) environment 에도 키를 더한다 — 빠뜨리면 배포 컨테이너에 값이 들어가지 않는다 ([deploy-guide.md](deploy-guide.md) §8).
3. PR 본문에 `Vault 등록 필요: KEY_NAME (dev/prod)` 를 적는다. **값은 적지 않는다.**
4. 머지 전에 Vault 에 dev 값을, 배포 전에 prod 값을 등록한다.
5. 이름을 바꾸면 위 세 곳을 같은 PR 에서 바꾼다.

## 5. 하지 말 것

- yml · 테스트 픽스처 · Swagger 예시 · 로그에 실값 — 테스트 키(`test_sk_`)도 마찬가지다
- 이슈 · PR · 커밋 메시지 · 리뷰 코멘트에 키를 일부라도 붙여넣기
- `.env` 를 다른 이름으로 복사해 두기 — `.gitignore` 가 `.env.*` · `*.env` · `.env-*` · `.env_*` 까지 막지만
  `env.txt` · `.envrc`(direnv) 같은 이름은 못 막는다. 커밋 전에 `git status` 로 확인한다

## 6. 유출됐을 때

1. **키를 먼저 폐기·재발급한다** (토스 상점관리자, 카카오·네이버 개발자 콘솔, DB·스토리지).
   서비스 토큰 비밀은 새 값으로 바꾸고 `INTERNAL_JWT_SECRET_PREVIOUS` 를 비운 채 서비스 5종을 바로 재기동한다 (`security-guide.md` §9-7).
2. Vault 값을 갱신하고 서비스를 재기동한다.
3. 커밋 히스토리 정리는 그다음이다. public 저장소에 한 번 올라간 값은 지워도 **이미 공개된 것으로 본다.**
