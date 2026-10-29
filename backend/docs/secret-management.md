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
| 시크릿 | PG 시크릿 키, JWT 개인키(auth 전용), 서비스 토큰 비밀(`INTERNAL_JWT_SECRET` · `_PREVIOUS` — 서비스 전부 공통, 게이트웨이 제외), 클라이언트 IP HMAC 키(`GATEWAY_CLIENT_IP_HASH_KEY` — 게이트웨이 전용, 인스턴스 공통, `gateway.client-ip.enabled` 를 켤 때만 필요), 로그인 · 가입 시도 이메일 HMAC 키(`AUTH_LOGIN_GUARD_HASH_KEY` — auth 전용, 인스턴스 공통, `auth.login-guard.enabled` 기본 켬이라 필수), DB·Redis 비밀번호, 소셜 client secret, 스토리지 키, 초기 관리자 시드(`ADMIN_SEED_*` — auth 전용), 관리자 OTP 비밀 암호화 키(`AUTH_OTP_SECRET_KEY` · `_PREVIOUS` — auth 전용, 인스턴스 공통, 항상 필수) | Vault |
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
- Claude API 키(#215, `ai.*`)는 `AI_API_KEY` 하나를 Vault 에 둔다 — AI 를 쓰는 서비스(operations 예정)에만 넣는다. 툴링이 자동으로 읽는
  `ANTHROPIC_API_KEY` 이름을 쓰지 않는다(개발 PC 셸의 개인 키가 서비스로 섞이지 않게). `AI_ENABLED` 는 비밀이 아니라 설정 스위치다 —
  배포 설정의 환경변수로 넣고 Vault 에 등록하지 않는다. 기본값은 꺼짐이고, 켜면 키 · 타임아웃이 있어야 기동한다 (`bootstrap-conventions.md` AI).
- Kafka(#304 · #310) 접속 주소 `KAFKA_BOOTSTRAP_SERVERS` 는 Vault 에 둔다 — 비밀은 아니지만 공유 클러스터의 서버 주소라 저장소에 쓰지 않는다.
  SASL 계정 `KAFKA_SASL_USERNAME` · `KAFKA_SASL_PASSWORD` 도 Vault 키로 예약한다(지금은 비움 — ACL 은 Infra 후속). `MESSAGING_ENABLED` · `KAFKA_NAMESPACE` ·
  `KAFKA_SECURITY_PROTOCOL` · `KAFKA_SASL_MECHANISM` 은 비밀이 아닌 배포 설정이다(`deploy-guide.md` §4-4). `KAFKA_PORT` 는 로컬 compose 전용이다.
- JWT 키 만들기 · 환경변수 한 줄 변환 · 교체 순서는 [security-guide.md](security-guide.md) §6 이 정본이다.
- 서비스 토큰 비밀(`INTERNAL_JWT_SECRET`)은 `openssl rand -base64 32` 로 만들고 서비스 전부에 **같은 값**을 넣는다. 게이트웨이에는 넣지 않는다.
  교체는 `INTERNAL_JWT_SECRET_PREVIOUS` 로 3단계를 밟는다 — 정본은 [security-guide.md](security-guide.md) §9-6 · §9-7.
- **클라이언트 IP HMAC 키(`GATEWAY_CLIENT_IP_HASH_KEY`, 게이트웨이 전용, #89 · #135)는 Vault 등록 필요(dev/prod)다.** `openssl rand -base64 32` 로 만들고
  게이트웨이 인스턴스 전부에 같은 값을 넣는다. 비로그인 버킷 키와 하류 `X-Client-Subject` 값(security-guide.md §7-3)을 만든다 — 서비스에는 넣지 않는다.
  스위치 `GATEWAY_CLIENT_IP_ENABLED` 와 `GATEWAY_TRUSTED_PROXIES`(BFF · LB 송신 주소)는 비밀이 아니라 배포 설정의 환경변수다 — Vault 에 등록하지 않는다.
- **Eureka 레지스트리 비밀번호(`EUREKA_PASSWORD`, #248)는 Vault 등록 필요(dev/prod)다.** `openssl rand -hex 32` 로 만들고 service-discovery · 게이트웨이 · 서비스 전부에
  **같은 값**을 넣는다(환경마다 다른 값). 클라이언트 URL 에 실리는 값이라 영문 · 숫자 · `. _ ~ -` 만 쓴다 — 다른 문자가 있으면 Eureka 가 뜨지 않는다.
  호스트 `EUREKA_HOST` 는 비밀이 아니지만 배포 env 로 같이 넣는다. 교체 순서는 [security-guide.md](security-guide.md) §12-4.
- **로그인 · 가입 시도 이메일 HMAC 키(`AUTH_LOGIN_GUARD_HASH_KEY`, auth 전용, #66)는 Vault 등록 필요(dev/prod)다.** `openssl rand -base64 32` 로 만들고
  auth 인스턴스 전부에 **같은 값**을 넣는다. `auth.login-guard.enabled` 가 기본 켬이고 yml 에 기본값이 없어(`${AUTH_LOGIN_GUARD_HASH_KEY}`) 비었거나 32바이트 미만이면
  auth 가 뜨지 않는다. 바꾸면 진행 중인 시도 창이 모두 초기화된다 — 교체 · 운영 주의는 [security-guide.md](security-guide.md) §14-4.
  다른 키(`INTERNAL_JWT_SECRET` · `GATEWAY_CLIENT_IP_HASH_KEY`)를 재사용하지 않는다.
  시도 제한을 끄는 운영 kill switch 는 **`AUTH_LOGIN_GUARD_ENABLED=false`(`.env` 또는 OS 환경변수) 하나**다 — 비밀이 아니라 설정 스위치라 Vault 에 등록하지 않는다.
  넣지 않으면 켬이고, `.env.example` 에는 빈 값 줄 없이 주석 예시만 둔다(빈 문자열은 기본값으로 가지 않고 바인딩에 실패해 auth 가 뜨지 않는다).
  끄면 모든 이메일의 무차별 대입이 열리므로 승인자 · 최대 시간을 정해 짧게만 쓴다 — 절차는 [security-guide.md](security-guide.md) §14-4 비상 절차.
- **관리자 OTP 비밀 암호화 키(`AUTH_OTP_SECRET_KEY` · `AUTH_OTP_SECRET_KEY_PREVIOUS`, auth 전용, #238)는 Vault 등록 필요(dev/prod)다.** `openssl rand -base64 32` 로 만들고
  auth 인스턴스 전부에 **같은 값**을 넣는다. 관리자 TOTP 비밀을 DB(`auth.admin_otp_credential.secret_ciphertext`)에 AES-256-GCM 으로 저장하는 키다 — AAD 는 용도 · 회원 · 자격(`admin-otp:v1:credential:{memberId}:{credentialId}`,
  미확정 등록은 `admin-otp:v1:enrollment:{memberId}`)이라 다른 관리자 행 · 같은 관리자의 옛 자격 행 · 다른 용도로 옮겨 붙인 암호문은 복호화되지 않는다.
  행마다 그 행을 암호화한 키의 식별자(`key_id` = base64url(SHA-256(키) 앞 8바이트), 11자)를 같이 둔다. OTP 를 쓰는 관리자가 없어도 **항상 필수**다 —
  yml 에 기본값이 없어(`${AUTH_OTP_SECRET_KEY}`) 비었거나 base64 로 정확히 32바이트가 아니면 auth 가 뜨지 않는다. 다른 키를 재사용하지 않는다.
  **테스트 키를 재사용하지 않는다** — `application-test.yml` · 테스트 코드의 키는 공개 저장소에 있어 누구나 안다. 디코드한 값에 `test-only` 가 들어 있으면 auth 가 기동하지 않고,
  이 검사를 끄는 `auth.admin-otp.allow-test-only-key` 는 테스트 설정에만 둔다(운영 · 개발 yml · 환경변수 · Vault 에 넣지 않는다).
  **키를 잃으면 등록된 OTP 를 모두 읽지 못한다**(로그인 2단계가 `AUTH_039` 503) — Vault 백업 대상이다. 발급자 이름 `AUTH_OTP_ISSUER` 는 비밀이 아닌 설정이라 Vault 에 넣지 않는다.

  회전은 서비스 토큰 비밀과 같은 3단계다. `_PREVIOUS` 는 복호화에만 쓰고, 현재 키와 같은 값이면 기동하지 않는다.

  1. `AUTH_OTP_SECRET_KEY_PREVIOUS` = **새 키**(현재 키는 그대로) → auth 전부 재기동. 모든 인스턴스가 새 키를 읽을 수 있게 된다(아직 옛 키로 쓴다)
  2. `AUTH_OTP_SECRET_KEY` = 새 키, `AUTH_OTP_SECRET_KEY_PREVIOUS` = **옛 키** → auth 전부 재기동. 이후 OTP 로그인이 성공할 때마다 그 행을 새 키로 다시 암호화한다
     (같은 조건부 UPDATE 에서 `secret_ciphertext` · `key_id` 를 바꾼다). 순서를 지키면 롤링 재기동 중에 어느 인스턴스도 다른 쪽 암호문을 못 읽는 순간이 없다.
     롤링 재기동 중에는 아직 옛 설정(현재 = 옛 키)인 인스턴스가 새 키 행을 "이전 키" 로 보고 옛 키로 되돌려 암호화할 수 있다 — 읽기는 양쪽 다 되고, 재기동이 끝난 뒤의 로그인이 다시 새 키로 옮기므로 3단계 count 확인으로 수렴한다
  3. `select count(*) from auth.admin_otp_credential where key_id <> '<새 키의 key_id>'` 가 0 이 되면 `AUTH_OTP_SECRET_KEY_PREVIOUS` 를 비우고 재기동한다.
     새 키의 `key_id` 는 `printf '%s' "$KEY" | base64 -d | openssl dgst -sha256 -binary | head -c 8 | base64 | tr '+/' '-_' | tr -d '='` 로 구한다.
     0 이 되기 전에 비우면 남은 관리자는 OTP 로그인이 `AUTH_039` 다 — 오래 로그인하지 않은 관리자는 OTP 초기화(#238 PR-C)로 다시 등록하게 한다

  키가 새면 같은 3단계를 바로 밟는다. 암호문만으로는 비밀을 못 읽지만 DB 와 키가 함께 새면 모든 관리자의 2단계 인증이 무력해진다.
- **초기 관리자 시드(`ADMIN_SEED_EMAIL` · `ADMIN_SEED_PASSWORD` · `ADMIN_SEED_NAME`, auth 전용, #6)는 Vault 등록 필요(dev/prod)다.**
  실제 관리자 이메일 · 비밀번호를 yml · 테스트 픽스처 · 이슈 · PR 에 쓰지 않는다 — yml 은 `${ADMIN_SEED_EMAIL:}` 플레이스홀더만 둔다.
  비밀번호는 12~64자, 영문 · 숫자 포함 공백 없는 ASCII 이고(`openssl rand -base64 24` 로 만들고 영문 · 숫자가 모두 들었는지 본다), 같은 이메일의 관리자가 이미 있으면 덮어쓰지 않으므로
  첫 배포로 관리자를 만든 뒤 Vault 값을 지워도 된다. 비밀번호 교체 API 는 아직 없다 — 바꾸려면 후속 이슈를 쓴다. 규칙 정본은 [security-guide.md](security-guide.md) §11-3.

## 3. 환경별 값의 출처

| 환경 | 출처 | 토스 키 |
|------|------|---------|
| local | `backend/.env` — `.env.example` 을 복사해 채운다 (커밋 금지) | `test_sk_` 또는 `test_gsk_`(결제위젯) 만 |
| dev | Vault | `test_sk_` 또는 `test_gsk_` 만 |
| prod | Vault | `live_sk_` 또는 `live_gsk_` 만 |

- 주입 방식(Spring Cloud Vault / Vault Agent → 환경변수)과 Vault 경로·인증 방식은 #32 에서 확정하고 이 절을 갱신한다.
- 배포 파이프라인(#198)은 Vault KV v2 `kv/commerce-foundry/backend/{dev,prod}/env`(환경당 secret 하나)를 AppRole 로 읽는다 —
  Jenkins credential `commerce-foundry-vault-role-id` / `commerce-foundry-vault-secret-id`, Vault 주소는 Jenkins 전역 env `VAULT_ADDR` ([jenkins-cicd-guide.md](jenkins-cicd-guide.md) §6).
- **DB 접속 정보(`DB_URL` · `DB_USERNAME` · `DB_PASSWORD`)는 환경 축으로 나눈다** — 키 이름은 모든 환경에서 같고 값만 환경별 secret 에 다르게 둔다.
  환경은 DB 서버로 나뉘고 database · 스키마 이름은 같으므로(`service-inventory.md` §4-2), 환경마다 바뀌는 것은 호스트 · 계정 · 비밀번호뿐이다.
  지금 경로는 환경당 secret 하나(`kv/commerce-foundry/backend/<env>/env`)다. 서비스별 DB 계정으로 나누면(#258) 서비스 축을 더할지(`.../<env>/<service>`) 또는 서비스별 키 이름으로 둘지를
  #32 에서 정한다. dev 와 prod 의 DB 계정 · 비밀번호를 같은 값으로 두지 않는다.
- **개발자 계정은 테스트 키만 다룬다.** 라이브 키는 고객사 계정 소유이고 Vault 에만 있다.
- 키가 뒤바뀐 채로 기동하면 실패시킨다 (`payment-guide.md` §2 — `TossPaymentsStartupGuard`).
- payment-service `application-test.yml` 의 `test_sk_dummyForContextTestsOnly000` · 테스트 코드의 `test_sk_abc123XYZ` 같은 값은 **형식만 맞춘 더미**다 —
  §5 가 금지하는 "실제 테스트 키" 가 아니다. 실제 테스트 키는 테스트 · yml 어디에도 넣지 않는다.

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
   서비스 토큰 비밀은 새 값으로 바꾸고 `INTERNAL_JWT_SECRET_PREVIOUS` 를 비운 채 서비스 전부를 바로 재기동한다 (`security-guide.md` §9-7).
2. Vault 값을 갱신하고 서비스를 재기동한다.
3. 커밋 히스토리 정리는 그다음이다. public 저장소에 한 번 올라간 값은 지워도 **이미 공개된 것으로 본다.**

### 소셜 로그인 자격 증명 (#5)

KAKAO_CLIENT_SECRET · NAVER_CLIENT_SECRET은 Vault에서 auth-service에만 주입한다.
CLIENT_ID · REDIRECT_URI도 환경마다 Vault 배포 설정으로 관리하며 .env.example에는 키 이름과 빈 값만 둔다.
AUTH_SOCIAL_ENABLED 및 제공자 LOGIN_ENABLED는 기본 false다. 켠 제공자는 ID · secret · 고정 redirect URI가 모두 있어야 기동한다.
provider access/refresh 토큰, 인가 code, state, verifier는 DB/로그/감사로그에 저장하지 않는다.
Redis에는 state SHA-256 지문과 provider/verifier SHA-256 바인딩만 5분 동안 둔다.
