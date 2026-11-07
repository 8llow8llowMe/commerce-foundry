# 운영자 비밀번호 재설정 (#589)

대표 관리자가 운영자에게 일회용 링크를 메일로 보내고, 수신자가 새 비밀번호를 정한다. SMTP 발송은 Spring `JavaMailSender`를 사용하며 SES SMTP와 호환한다.
이 문서는 auth-service 내부 구현과 운영·FE 계약을 기록한다.

## 설정

`auth.admin-password-reset` 설정을 사용한다. 기본값은 비활성화다. SMTP 자격은 Vault에서 환경변수로 주입하며 실제 값은 저장소에 기록하지 않는다.

| 환경변수 | 프로퍼티 | 의미 |
|---|---|---|
| `AUTH_ADMIN_PASSWORD_RESET_ENABLED` | `enabled` | 활성화 여부, 기본 `false` |
| `AUTH_ADMIN_PASSWORD_RESET_URL` | `reset-url` | FE 재설정 화면의 고정 HTTPS URL |
| `AUTH_ADMIN_PASSWORD_RESET_SMTP_HOST` | `smtp-host` | SMTP 호스트 |
| `AUTH_ADMIN_PASSWORD_RESET_SMTP_PORT` | `smtp-port` | SMTP 포트, 기본 587 |
| `AUTH_ADMIN_PASSWORD_RESET_SMTP_USERNAME` | `smtp-username` | SMTP 인증 사용자 |
| `AUTH_ADMIN_PASSWORD_RESET_SMTP_PASSWORD` | `smtp-password` | SMTP 인증 비밀번호 |
| `AUTH_ADMIN_PASSWORD_RESET_SENDER` | `sender` | 발신 이메일 주소 |

켜면 모든 필수값과 포트 범위를 기동 시 검사한다. 재설정 URL은 호스트가 있는 HTTPS 주소여야 하며 userinfo·query·fragment를 허용하지 않는다.
`token-ttl`은 기본 15분이며 1~30분 범위다. SMTP는 인증과 STARTTLS를 필수로 사용하고 서버 인증서를 검증한다.
연결 타임아웃은 3초, 읽기·쓰기 타임아웃은 각각 5초다. 메일 디버그 로그는 끈다.

## API와 FE 계약

두 API 모두 기존 `Response` 응답 봉투와 `Cache-Control: no-store`를 사용한다. 성공 본문에 토큰·이메일을 싣지 않는다.

### 링크 발송

`POST /api/v1/admin/admins/{memberId}/password-reset-link`

- 본문 없음. `STAFF_MANAGE` 권한이 필요하고, DB에서도 행위자가 활성 대표 관리자(ADMIN·OWNER)인지 확인한다.
- 활성 운영자에게만 발송한다. 대상 없음·고객·비활성·SMTP 발송 실패는 모두 같은 HTTP 200 성공 봉투다. FE는 발송 여부를 확정하는 문구를 표시하지 않는다.
- 발송 시도는 Redis 고정 창으로 전체 100회/15분, 행위자 10회/15분, 대상 3회/15분으로 제한한다. 계정 존재 여부와 무관하게 센다.
- SMTP 실패는 실패 감사 기록을 남기고 해당 링크를 폐기한다. Redis 제한 저장소 장애는 대상 조회 전에 HTTP 503 `AUTH_013`이다.

### 비밀번호 재설정

`POST /api/v1/auth/admin/password-reset` — 공개 API

```json
{"token":"메일 링크 프래그먼트의 토큰", "newPassword":"새 비밀번호"}
```

- 메일 링크는 서버 설정 URL에 `#token=<token>`을 붙인다. FE는 fragment를 읽어 JSON 본문으로 전달하고 화면 URL에서 제거한다. query로 바꾸거나 로그·분석 도구에 전달하지 않는다.
- 새 비밀번호는 10~64자, 공백 없는 ASCII이며 영문·숫자를 각각 포함한다. 요청 DTO의 문자열 표현에도 비밀번호·토큰을 넣지 않는다.
- 게이트웨이가 주입한 `X-Client-Subject`가 `[A-Za-z0-9_-]{22}` 형식이면 해당 주체별 10회/1분을 센다. FE가 이 헤더를 만들지 않는다.
- 헤더가 없거나 형식이 다르면 IP 차원 제한을 건너뛴다. 공개 사용 요청에 전체 공유 버킷을 두지 않아 한 호출자가 모든 운영자의 재설정을 막지 못하게 한다.
- 256비트 난수의 base64url 43자 형식과 Redis `GETDEL`로 받은 유효 grant를 통과한 뒤에만 BCrypt를 실행한다. 사용 가능한 링크 하나당 한 요청만 다음 단계로 진행한다.
- Redis에는 SHA256 지문과 `memberId:memberVersion`만 TTL과 함께 저장한다. 잠근 최신 회원 행의 버전·활성 운영자 상태를 검사해 먼저 발급된 다른 링크도 비밀번호 변경 후 거절한다.
- 만료·위조·재사용·회원 상태나 버전 변경은 같은 HTTP 400 `AUTH_061`이다. 시도 초과는 HTTP 429 `AUTH_062`, 기능 비활성은 HTTP 503 `AUTH_063`이다.
- 성공 후 기존 로그인은 차단하며 새 토큰을 발급하지 않는다. FE는 다시 로그인하도록 안내한다.

## 처리 순서와 장애 복구

사용 순서는 **링크 소비 → 비밀번호 DB 커밋 → 기존 access 컷오프 → 감사**다. Redis·SMTP·BCrypt를 DB 트랜잭션 밖에 두고 행 잠금과 비밀번호 저장만 짧은 트랜잭션으로 처리한다.
컷오프는 현재 시각을 초 단위로 내린 뒤 1초를 더해 변경과 같은 초에 발급된 기존 토큰까지 거절한다.

컷오프 저장이 실패하면 비밀번호는 이미 바뀌었고 링크는 소진된 상태로 HTTP 503 `AUTH_013`을 반환한다. 같은 링크 재시도로 복구되지 않는다.
새 링크를 보내 다시 재설정하거나 별도 운영 절차로 컷오프를 완료해야 한다. 그때까지 기존 access 토큰은 자연 만료 전에 계속 유효할 수 있다.
DB 저장 실패도 소비한 링크를 복구하지 않으므로 새 링크가 필요하다.

감사 행위는 `ADMIN_PASSWORD_RESET_LINK_SEND`와 `ADMIN_PASSWORD_RESET_USE`다. 행위자·대상 회원 ID·결과만 기록하고 이메일·원문 토큰·비밀번호·메일 예외 원문은 기록하지 않는다.
실제 SMTP 발송과 Redis 컨테이너 검증은 해당 인프라가 있는 환경에서 수행한다. 엔티티와 운영 DDL 변경은 없다.
