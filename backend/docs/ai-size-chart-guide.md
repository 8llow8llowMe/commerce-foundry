# AI 사이즈표 초안·생성·이관 계약 (#217)

## 1. 범위와 현재 상태

상품 사이즈표의 정본은 product-service의 기존 `sizechart` 컨텍스트다(#216). AI 출력은 정본에 바로 쓰지 않는다.
operations-service의 작업 엔진(#212)과 알림 센터(#213), ai-core(#215)를 재사용한다.
이 문서는 #217의 구현 명세다. 현재 변경은 TEXT 입력의 생성·초안·운영자 적용 경계만 연결한다.
IMAGE 이관과 일괄 제출은 후속 범위이며 API가 열리지 않는다. 실제 운영 AI 호출은 별도 설정·배포 검증 뒤에만 한다.

| 소유자 | 책임 |
|---|---|
| product-service `sizechart` | 입력·템플릿 스냅숏, 초안, 검증·경고, 운영자 확인·확정, 요청 멱등성과 published chart revision |
| operations-service `sizechartgeneration` | 기능 전용 작업 등록 영수증, AI 핸들러·결과, 재시도·lease, 기존 완료 알림 |
| ai-core | 설정된 Provider 호출, 텍스트 마스킹, 구조화 출력, 사용량 계량 |

## 2. ADMIN·internal 계약

공개 고객 사이즈표와 기존 어드민 GET/PUT 응답은 보존한다. 새 관리자 API는 `Response<T>` 봉투와 문자열 ID를 쓴다.

| 경로 | 권한 | 성공·의미 |
|---|---|---|
| `POST /api/v1/admin/products/{productId}/size-chart/drafts` | `CATALOG_MANAGE` | 필수 UUID `Idempotency-Key`, `{templateId, mode:TEXT, sourceText}` → 202 `{draftId, jobId}` |
| `GET /api/v1/admin/size-chart-drafts/{draftId}` | `CATALOG_READ` | 상태·버전·템플릿 스냅숏·후보 행/칸·경고·작업 ID. 원문 프롬프트·이미지 바이트는 제외 |
| `GET /api/v1/admin/size-chart-drafts?productId=&requestedByMe=&page=&size=` | `CATALOG_READ` | 복구용 초안 목록. 최신순·페이지 조회이며 각 행 때문에 operations를 호출하지 않음 |
| `POST /api/v1/admin/size-chart-drafts/{draftId}/refresh` | `CATALOG_MANAGE` | 끝난 작업의 검증된 결과를 초안에 반영. 아직 실행 중이면 현재 초안 상태 반환 |
| `POST /api/v1/admin/size-chart-drafts/{draftId}/apply` | `CATALOG_MANAGE` | `{expectedDraftVersion, correctedChart, acknowledgedWarningIds}`를 검증해 상품 사이즈표 전체 교체 |
| `POST /api/v1/admin/size-chart-draft-batches` (후속, 미구현) | `CATALOG_MANAGE` | 최대 20개 상품의 개별 초안·작업 등록. 항목별 성공·실패와 안정적인 재시도 ID 반환 |

현재 생성 입력은 운영자가 선택·입력한 치수 텍스트다. 상품 상세의 텍스트를 쓰려면 운영자가 차트 부분만 선택해 보내야 한다.
서버는 승인한 한·영 측정 항목 어휘와 사이즈·수치 외 자유 문장 및 임의 템플릿 항목 이름을 AI 경로에서 거절한다.
자동 HTML 추출과 이미지 이관은 후속 범위다.
본문의 임의 URL을 따라가지 않는다. 템플릿 열 ID·단위와 상품 ID는 서버에서 다시 확인한다.
부분 치수는 초안에 남기되 빠진 칸을 0이나 추정값으로 채우지 않는다. 비정상적인 크기·순서는 경고 코드로 표시한다.
적용 요청은 빠진 칸을 모두 채우고 기존 #216의 1~30행·1~20열·값 범위·소수 둘째 자리 규칙을 통과해야 한다.

operations-service에는 범용 job 등록 API를 만들지 않는다. `POST /internal/v1/size-chart-generation-jobs`는
`SERVICE_product-service`만 허용하며 draft ID·입력 해시·검증된 요청자 ID만 받는다.
`GET /internal/v1/size-chart-generation-jobs/{jobId}/result`도 같은 호출자만 허용한다. 실행 중에는 `PENDING`, 성공 시에는 확정된 결과만 반환한다.
product-service의 `GET /internal/v1/size-chart-drafts/{draftId}/generation-input`은 `SERVICE_operations-service`만 허용한다.
job payload·진행 문구·오류·알림에는 원문 치수·이미지·개인정보를 싣지 않고 내부 ID만 담는다.

## 3. 상태·원자성·재시도

초안은 `REQUESTED → READY | FAILED | CANCELLED`, `READY → APPLIED`다. 만료 뒤 적용·수정은 거절한다.
작업 상태·lease·재시도는 operations-service의 `job`이 소유하며 초안 상태와 혼동하지 않는다.

1. product 트랜잭션이 요청자/멱등 키·정규 입력 해시, 상품·템플릿과 기존 표의 revision, 안전한 입력 스냅숏을 저장한다.
2. 트랜잭션 밖에서 기능 전용 internal API로 작업을 등록한다. operations는 draft ID별 고유 영수증과 job을 한 트랜잭션으로 저장한다.
3. 같은 운영자·키·입력 해시는 같은 draft/job을 돌려준다. 같은 키의 다른 입력은 409다. 완료 뒤에도 영수증을 보존한다.
4. 등록 응답이 유실되면 안정적인 draft ID와 오류를 돌려주고 같은 키의 재시도로 영수증을 다시 읽는다.
   `503`의 `Location`은 `/api/v1/admin/size-chart-drafts/{draftId}`다. 등록 전 실패로 남은 초안은 만료 대상이며
   새 유료 job을 임의로 만들지 않는다. 기존 작업 여부는 operations 영수증으로 먼저 대조한다.
5. handler는 product internal API에서 불변 입력을 읽고 AI를 호출해 검증된 구조화 결과를 `JobOutcome`으로 반환한다.
   긴 호출 앞뒤에 checkpoint를 둔다. JSON 성공 확정은 워커·시도와 유효 lease를 함께 확인한다.
6. job 종료 알림은 기존 `JobFinishedEvent`를 쓴다. 알림은 생성 작업 완료를 뜻하며 초안 적용 완료를 뜻하지 않는다.
7. 초안 GET은 product DB의 저장 상태만 읽고 operations를 호출하거나 상태를 바꾸지 않는다. refresh 또는 apply가 결과를 초안에 저장한다.
   결과 조회는 외부 I/O이므로 DB 트랜잭션 밖에서 하고, 적용은 초안 상태·버전·만료를 다시 검사한다.
8. 적용은 템플릿 잠금 → 상품 표 잠금 순서를 유지한다. 초안을 만든 뒤 템플릿·상품 표가 바뀌었으면 409다.
   검토한 후보 저장·상품 표 교체·`APPLIED` 전이는 한 product 트랜잭션이다. 보관 기간 안의 동일 적용 재전송은 원래 결과를 돌려준다.

모든 수동 표 교체는 상품 표 revision을, 모든 템플릿 편집은 템플릿 revision을 올린다.
표가 없던 시점의 초안도 다른 운영자의 신규 표 생성과 경합하면 거절한다. AI는 사람의 새 편집을 덮지 않는다.
operations의 결과 보관은 초안 만료보다 길고 등록 영수증의 보관은 동일 키 재시도 창보다 길어야 한다.
결과가 먼저 만료되면 명시적 복구 불가 상태로 처리하며 다른 유료 작업을 조용히 등록하지 않는다.
초안 내용과 product 측 멱등 키의 유효 기간은 기본 7일이다. 그 시각부터 상세·생성 입력·refresh·apply는 410으로 내용 없이 거절하고
목록에서는 제외한다. 기본 시간당 최대 1,000행의 만료 초안을 조건부로 물리 삭제한다. 삭제가 늦어져도 읽기 차단은 즉시 적용된다.
삭제 뒤 같은 운영자·키의 제출은 **새 draft/job을 만들고 새 과금이 생길 수 있다**. 클라이언트는 7일이 지난 키를 자동 재시도하지 않는다.
operations의 기존 draft ID별 등록 영수증과 job 결과는 최소 90일 유지하며, 옛 결과를 새 draft ID에 결합하지 않는다.

새 오류 접두어는 `SIZE_CHART_DRAFT_`다. 없는 초안 `001`(404), 동일 키 다른 입력 `002`(409),
버전·바인딩 `003`(409), 적용 불가 상태 `004`(409), 만료 `005`(410), 템플릿 변경 `006`(409),
상품 표 변경 `007`(409), 다른 내용의 적용 재생 `008`(409), 경고 확인 누락 `009`(409),
결과 보관 만료 `010`(410), 안전하지 않은 입력·이미지 `030`(400), 등록 불명 `501`(503),
결과 조회 장애 `502`(503), 내부 결과 계약 오류 `503`(502)다. 필드별 검증 코드는 별도 `1xx`다.

## 4. AI·이미지 안전 경계

operations의 기존 `AiClient` 구조화 출력과 설정된 `size-chart` 기능을 쓴다. SDK·Provider를 서비스가 직접 만들지 않는다.
AI 출력은 불신 입력이다. 서버가 템플릿 열 소속, 크기·행 수·중복, 숫자 범위와 경고 코드를 다시 검증한다.
원문 출력·프롬프트·이미지 바이트·Provider 오류 본문은 로그·감사·작업 결과에 남기지 않는다.
텍스트는 운영자가 차트 부분만 선택해 제출한다. 공통 마스커와 승인 측정 어휘 검사로 알려진 패턴을 가리고
가릴 수 없는 이름·주소가 섞인 자유 문장 및 템플릿 이름은 AI 경로에서 거절한다. #216 수동 템플릿 편집은 제한하지 않는다.

`AiImage`는 이미지 안의 글자를 마스킹하지 않는다. **현재 IMAGE mode는 양 서비스에서 거절한다.** 후속 이관은 운영자가 개인정보 제거를 확인한
사이즈표 자산의 **정확한 바이트 해시**를 승인한 경우에만 허용한다. 승인 뒤 자산이 바뀌면 다시 승인해야 한다.
임의 외부 URL·HTML `<img>`를 직접 다운로드하지 않는다. 관리되는 상품 이미지 키만 소속·크기·MIME·magic bytes·ETag로 검증한다.
기존 이관 이미지처럼 관리 키가 없는 자료는 안전한 재등록·승인 전까지 일괄 AI 전송 대상에서 제외한다.
승인 없는 이미지와 알 수 없는 고객 개인정보가 든 이미지의 자동 이관은 열지 않는다.

## 5. 검증·배포 경계

- 단위: 누락·이상 치수, 템플릿 매핑, 숫자·단위, 구조화 응답 거절, 개인정보 가림과 입력 차단.
- product DB: 멱등 키 경합, 초안·차트 revision, 중복 적용, 수동 편집 경합, 행·칸 전체 롤백과 실제 PostgreSQL DDL.
- operations DB: 완료 뒤 같은 draft 등록 재생, 중복 작업 억제, lease 만료·취소 경합, 실패 재시도와 job 완료 알림.
- 통합: 등록 응답 유실, 결과 조회 장애, 작업은 성공했으나 product가 일시 중단된 경우, 일괄 부분 실패와 재시도.
- 보안: ADMIN 역할·internal 허용 호출자, 이미지 소속·형식·용량, 로그·감사에 개인정보·바이트·서명 URL 없음.

새 엔티티와 revision 컬럼의 실제 운영 DDL은 PostgreSQL 17 생성 스냅숏으로 확인해 해당 PR 본문에 싣는다.
운영 DB에 additive DDL을 먼저 적용하고 모든 인스턴스를 호환 바이너리로 바꾼 뒤 기능을 연다.
메시징·AI 또는 필요한 내부 서비스가 꺼져 있으면 작업을 성공한 것처럼 응답하거나 초안을 자동 적용하지 않는다.
