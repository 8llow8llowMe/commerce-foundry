# 마케팅 동의 조회 계약 (#230 PR-A)

auth-service가 고객 정보 수정(`PATCH /api/v1/members/me`)과 가입에서 저장한 `member_agreement` 이력이 정본이다.
이번 범위는 발송 담당 서비스가 최신 동의를 일괄 확인할 internal API다. 캠페인 등록·실제 발송 연결은 후속 작업이다.

## 요청과 응답

`POST /internal/v1/marketing-consents/query`는 operations-service 서비스 토큰만 받는다. 사용자 access 토큰은 ADMIN도 401 `SECURITY_007`,
다른 서비스는 403 `SECURITY_005`다. Swagger 고객/어드민 그룹과 외부 게이트웨이 라우트에는 노출하지 않는다.

```json
{"memberIds": [2, 1, 2, 3]}
```

중복을 포함한 원문 목록은 1~100개이며 각 ID는 null이 아닌 양수 long이다. 중복은 첫 등장만 남기고 요청 순서를 유지한다.
응답은 공통 `Response` 봉투의 `dataBody`에 다음 목록을 담는다. ID는 Presenter에서 문자열로 변환한다.

```json
{"members": [{"memberId": "2", "marketingAgreed": true}, {"memberId": "1", "marketingAgreed": false}, {"memberId": "3", "marketingAgreed": false}]}
```

이 API는 상태 판정이라 요청 ID를 누락시키지 않는다. 없는 회원, ADMIN, `deactivated_at`이 있는 계정, 동의 이력 없음, 거절·철회는 모두 false다.
이름·이메일·전화·계정 존재 여부 별도 필드·약관 본문·동의 원문은 반환하지 않는다. 요청 ID 목록을 URL에 넣지 않는 본문형 조회로 둔다.
요청·응답 본문을 운영 로그에 추가하지 않는다.

## 최신 판정과 질의

MARKETING 유형 중 `created_at`이 가장 최근인 행을 고르고, 같은 시각이면 가장 큰 이력 ID를 고른다. 현재 프로필 조회의 정렬과 같다.
과거에 true였더라도 그 뒤 false가 있으면 false다. 이후 다시 동의하면 새 true가 최신 상태가 된다. 다른 약관 유형의 이력은 영향을 주지 않는다.

`MarketingConsentQueryAdapter`는 기존 `member_agreement(member_id, agreement_type)` 인덱스의 선두 조건과 회원 PK로 범위를 좁히는 단일 JPQL로
회원 존재·CUSTOMER 역할·비활성화 시각과 최신 true 여부를 확인한다. 회원별 반복 조회를 하지 않고 회원 버전·동의 이력을 수정하지 않는다.
엔티티·인덱스·스키마 변경은 없다. PostgreSQL 실행 계획 실측은 이번 범위의 근거에 포함하지 않는다.

## 발송 서비스 후속 계약

- 이 응답은 조회 문장 시점의 스냅숏이다. 조회 후 철회와 실제 외부 발송을 원자적으로 묶지 않는다.
- 발송 실행 직전에 조회하고 false·응답 누락·조회 실패는 발송 보류로 처리한다. 예약 당시 동의 상태만으로 보내지 않는다.
- 채널별 수신 동의, 야간·빈도 제한, 운영자 승인, 중복 발송 방지와 실패 재시도는 발송 서비스의 별도 정책으로 구현한다.
- 현재 MARKETING 하나의 저장 상태만 제공한다. 채널별 동의나 발송 조건을 이 boolean 하나로 새로 결정하지 않는다.
- 조회 후 철회 경합까지 처리하는 발송 프로토콜과 실발송 검증이 완료될 때까지 #230 전체를 완료 처리하지 않는다.

## 검증 범위

MVC는 호출 서비스·목록/원소 검증·응답 필드와 100개 경계를 본다. 실제 서명 토큰 스모크는 operations만 허용되는지 검증한다.
H2와 PostgreSQL 17의 공통 DB 계약은 철회·재동의·시각/ID 경계·회원/역할 제외·중복과 순서·SQL 1회·회원 버전 불변을 확인한다.
Docker 없는 로컬의 PostgreSQL 계약은 skip으로 구분하고 CI에서 실행한다.

## 관리자 수신자 미리보기 (#230 PR-B)

operations-service의 `POST /api/v1/admin/campaigns/recipient-previews`는 ADMIN access 토큰만 허용한다.
요청은 위 internal API와 같은 `{memberIds}`이며, 중복 포함 1~100개·원소 양수 필수다. 400 검증 코드는
`CAMPAIGN_101`(필수), `CAMPAIGN_102`(최대100), `CAMPAIGN_103`(원소 null·0·음수)이고 깨진 JSON은 `CAMPAIGN_100`이다.
공통 `Response`의 `dataBody`는 다음과 같다.

```json
{"recipientCount":2,"agreedCount":1,"excludedCount":1,"members":[{"memberId":"2","marketingAgreed":true},{"memberId":"1","marketingAgreed":false}]}
```

첫 등장 순서로 중복 제거한 전체 수·동의 수·제외 수다. auth 서비스 토큰으로 한 번 호출하며 요청자 access 토큰을 전달하지 않는다.
응답 순서와 무관하게 요청 순서를 유지한다. 성공 봉투·목록·Boolean 필수, 요청 집합과 응답 집합이 정확히 같아야 한다.
누락·중복·미요청·잘못된 ID·null 상태, 원격401/403/404/5xx·timeout·서킷 오픈은 모두503 `CAMPAIGN_001`이다.
원격 메시지·원문을 반환하거나 로그에 남기지 않는다. 기본 connect2초/read3초, `auth-service` 서킷, 자동 재시도 없음이다.
Facade는 원격 I/O를 DB 트랜잭션으로 감싸지 않는다. 업무 도달 후 성공·실패는 `CAMPAIGN_RECIPIENT_PREVIEW` 감사로 기록한다.

FE는 동의 확인 결과와 제외 인원을 보여 줄 수 있다. 조회 시점의 미리보기이며 발송 승인·예약·실행 보장이 아니다.
100개 이하의 단일 호출 조회로 한정하며, 영속 캠페인·배치 작업·실제 전송·동의 수정은 수행하지 않는다.
발송 직전 재검증과 철회 경합·채널·야간·빈도·중복·승인 정책은 위 후속 계약에 남는다.
