# AI 사이즈표 초안·생성·이관 계약 (#217)

## 1. 범위와 현재 상태

상품 사이즈표의 정본은 product-service의 기존 `sizechart` 컨텍스트다(#216). AI 출력은 정본에 바로 쓰지 않는다.
operations-service의 작업 엔진(#212)과 알림 센터(#213), ai-core(#215)를 재사용한다.
이 문서는 #217의 구현 명세다. TEXT 입력의 생성·초안·운영자 적용 경계가 연결되어 있다.
IMAGE 입력은 product-service 쪽(후보·확인·해시 재검증·internal 바이트 읽기)만 구현되어 있고 플래그 `size-chart.draft.image-enabled`(기본 `false`)로 닫혀 있다.
operations-service의 IMAGE 생성 경로(#217 PR2)와 운영 검증이 끝난 뒤에만 켠다. 닫혀 있으면 IMAGE 요청은 400 `SIZE_CHART_DRAFT_011`이다.
일괄 제출은 후속 범위이며 API가 열리지 않는다. 실제 운영 AI 호출은 별도 설정·배포 검증 뒤에만 한다.

| 소유자 | 책임 |
|---|---|
| product-service `sizechart` | 입력·템플릿 스냅숏, 초안, 검증·경고, 운영자 확인·확정, 요청 멱등성과 published chart revision |
| operations-service `sizechartgeneration` | 기능 전용 작업 등록 영수증, AI 핸들러·결과, 재시도·lease, 기존 완료 알림 |
| ai-core | 설정된 Provider 호출, 텍스트 마스킹, 구조화 출력, 사용량 계량 |

## 2. ADMIN·internal 계약

공개 고객 사이즈표와 기존 어드민 GET/PUT 응답은 보존한다. 새 관리자 API는 `Response<T>` 봉투와 문자열 ID를 쓴다.

| 경로 | 권한 | 성공·의미 |
|---|---|---|
| `POST /api/v1/admin/products/{productId}/size-chart/drafts` | `CATALOG_MANAGE` | 필수 UUID `Idempotency-Key`, `{templateId, mode:TEXT, sourceText}` 또는 `{templateId, mode:IMAGE, images:[{sourceId, approvedContentHash}] (1~4장), personalInfoRemovedConfirmed:true}` → 202 `{draftId, jobId}` |
| `GET /api/v1/admin/products/{productId}/size-chart/image-sources` | `CATALOG_READ` | IMAGE 후보 `[{sourceId, previewUrl, eligible, reason{code,name,description}}]`. 내려받지 않음 |
| `GET /api/v1/admin/products/{productId}/size-chart/image-sources/{sourceId}/inspection` | `CATALOG_MANAGE` | 이 상품 후보의 관리 키만 읽어 `{sourceId, contentHash(SHA-256), contentType, contentLength}`. 승인 흐름이고 요청마다 최대 5MiB 를 읽어 GET 이지만 관리 권한 |
| `GET /api/v1/admin/size-chart-drafts/{draftId}` | `CATALOG_READ` | 상태·버전·템플릿 스냅숏·후보 행/칸·경고·작업 ID. 원문 프롬프트·이미지 바이트는 제외 |
| `GET /api/v1/admin/size-chart-drafts?productId=&requestedByMe=&page=&size=` | `CATALOG_READ` | 복구용 초안 목록. 최신순·페이지 조회이며 각 행 때문에 operations를 호출하지 않음 |
| `POST /api/v1/admin/size-chart-drafts/{draftId}/refresh` | `CATALOG_MANAGE` | 끝난 작업의 검증된 결과를 초안에 반영. 아직 실행 중이면 현재 초안 상태 반환 |
| `POST /api/v1/admin/size-chart-drafts/{draftId}/apply` | `CATALOG_MANAGE` | `{expectedDraftVersion, correctedChart, acknowledgedWarningIds}`를 검증해 상품 사이즈표 전체 교체 |
| `POST /api/v1/admin/size-chart-draft-batches` (후속, 미구현) | `CATALOG_MANAGE` | 최대 20개 상품의 개별 초안·작업 등록. 항목별 성공·실패와 안정적인 재시도 ID 반환 |

TEXT 생성 입력은 운영자가 선택·입력한 치수 텍스트다. 상품 상세의 텍스트를 쓰려면 운영자가 차트 부분만 선택해 보내야 한다.
서버는 승인한 한·영 측정 항목 어휘와 사이즈·수치 외 자유 문장 및 임의 템플릿 항목 이름을 AI 경로에서 거절한다.
자동 HTML 텍스트 추출과 일괄 이미지 이관은 후속 범위다.

IMAGE 입력은 아래 순서를 따른다. 응답·오류·로그에는 스토리지 키·바이트·프롬프트를 싣지 않는다(미리보기는 우리 공개 주소만).

1. **후보** — 상품의 대표·갤러리 이미지(`IMG-{imageId}`)와 국문·영문 상세 본문 `<img src>`(`DESC-{주소 SHA-256 앞 32자}`)를 모은다(최대 40개).
   `storage_key`가 이 상품 경로(`products/{productId}/…`)인 이미지, 본문 중 공개 저장소 주소로 키를 정확히 역산할 수 있는 미디어(`products/media/…`)·이 상품 이미지만
   `eligible=true`다. 관리 키가 없는 이관 이미지 `LEGACY_IMAGE`, 외부 주소 `EXTERNAL_IMAGE`(미리보기 없음), 다른 상품 경로 `OTHER_PRODUCT_IMAGE`는 사유만 돌려준다.
2. **확인(inspection)** — 요청마다 현재 상품에서 후보를 다시 만들어 소속을 확인한 뒤 HEAD → ETag 조건 상한 읽기(storage-core `read`, 5MiB) →
   magic bytes(JPEG·PNG·WebP, GIF·SVG 거절) → SHA-256 소문자 16진수를 돌려준다. 확장자·저장된 Content-Type은 믿지 않는다.
3. **생성** — 운영자가 개인정보 제거를 확인(`personalInfoRemovedConfirmed=true`)하고 확인값을 `approvedContentHash`로 보낸다.
   멱등 요청 해시는 `[productId, templateId, "IMAGE", [[sourceId, approvedContentHash]…], true]`다(순서 포함, TEXT 해시 모양은 불변).
   신규 생성이면 트랜잭션 밖에서 바이트를 다시 읽어 SHA-256이 승인 값과 같을 때만 저장한다. 다르면 409 `SIZE_CHART_DRAFT_015`이고 초안을 만들지 않는다.
   저장 입력은 `{"mode":"IMAGE","images":[{"key","sha256","contentType","length"}]}`이며 기존 `source_json` 열을 쓴다(DDL 변경 없음).
본문의 임의 URL을 따라가지 않는다. 템플릿 열 ID·단위와 상품 ID는 서버에서 다시 확인한다.
부분 치수는 초안에 남기되 빠진 칸을 0이나 추정값으로 채우지 않는다. 비정상적인 크기·순서는 경고 코드로 표시한다.
적용 요청은 빠진 칸을 모두 채우고 기존 #216의 1~30행·1~20열·값 범위·소수 둘째 자리 규칙을 통과해야 한다.

operations-service에는 범용 job 등록 API를 만들지 않는다. `POST /internal/v1/size-chart-generation-jobs`는
`SERVICE_product-service`만 허용하며 draft ID·입력 해시·검증된 요청자 ID만 받는다.
`GET /internal/v1/size-chart-generation-jobs/{jobId}/result`도 같은 호출자만 허용한다. 실행 중에는 `PENDING`, 성공 시에는 확정된 결과만 반환한다.
product-service의 `GET /internal/v1/size-chart-drafts/{draftId}/generation-input`은 `SERVICE_operations-service`만 허용한다.
응답에 `mode`, `imageCount`, `images[{index, sha256, contentType}]`를 더한다(TEXT는 `TEXT`, 0, 빈 배열로 하위 호환). IMAGE의 `sourceJson`은 키를 뺀 순서·해시·형식만이다.
`GET /internal/v1/size-chart-drafts/{draftId}/generation-images/{index}`도 같은 호출자만 허용하며 봉투 없는 `application/octet-stream` 바이트를 `no-store`로 돌려준다.
읽는 시점에 SHA-256·형식·길이를 다시 대조하고, 바뀌었거나 사라졌으면 재시도해도 성공할 수 없는 410 `SIZE_CHART_DRAFT_016`이다.
오류는 Accept 와 관계없이(`application/octet-stream` 만 보내도) 원래 상태 코드의 JSON 봉투로 나간다 — 예외 핸들러가 Content-Type 을 고정한다.
저장소 장애는 재시도 가능한 503 `SIZE_CHART_DRAFT_504`다. 플래그를 닫으면 이미 만든 IMAGE 초안의 바이트도 내보내지 않는다(400 `011`).
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
결과 보관 만료 `010`(410), IMAGE 기능 닫힘 `011`(400), 이 상품에 없는 이미지 `012`(404), 쓸 수 없는 이미지 `013`(400),
형식·크기 거절 `014`(400), 승인 뒤 이미지 변경 `015`(409), internal 생성 이미지 변경 `016`(410, 재시도 불가),
안전하지 않은 입력·이미지 `030`(400), 등록 불명 `501`(503), 결과 조회 장애 `502`(503), 내부 결과 계약 오류 `503`(502),
이미지 저장소 장애 `504`(503)다. 필드별 검증 코드는 별도 `1xx`다(IMAGE: 이미지 수·중복 `112`, 이미지 아이디 `113`, 확인값 `114`,
개인정보 확인 `115`, IMAGE에 텍스트 `116`, TEXT에 이미지 `117`).

## 4. AI·이미지 안전 경계

operations의 기존 `AiClient` 구조화 출력과 설정된 `size-chart` 기능을 쓴다. SDK·Provider를 서비스가 직접 만들지 않는다.
AI 출력은 불신 입력이다. 서버가 템플릿 열 소속, 크기·행 수·중복, 숫자 범위와 경고 코드를 다시 검증한다.
원문 출력·프롬프트·이미지 바이트·Provider 오류 본문은 로그·감사·작업 결과에 남기지 않는다.
텍스트는 운영자가 차트 부분만 선택해 제출한다. 공통 마스커와 승인 측정 어휘 검사로 알려진 패턴을 가리고
가릴 수 없는 이름·주소가 섞인 자유 문장 및 템플릿 이름은 AI 경로에서 거절한다. #216 수동 템플릿 편집은 제한하지 않는다.

`AiImage`는 이미지 안의 글자를 마스킹하지 않는다. **operations-service는 아직 IMAGE mode를 거절하고(PR2), product-service는 플래그가 닫혀 있다.**
IMAGE는 운영자가 개인정보 제거를 확인한 사이즈표 자산의 **정확한 바이트 해시**를 승인한 경우에만 허용한다. 승인 뒤 자산이 바뀌면 다시 승인해야 한다
(생성 시 409 `015`, 생성 작업이 읽는 시점 410 `016`).
임의 외부 URL·HTML `<img>`를 직접 다운로드하지 않는다. 관리되는 상품 이미지 키만 소속·크기·MIME·magic bytes·ETag로 검증한다.
기존 이관 이미지처럼 관리 키가 없는 자료는 안전한 재등록·승인 전까지 일괄 AI 전송 대상에서 제외한다.
승인 없는 이미지와 알 수 없는 고객 개인정보가 든 이미지의 자동 이관은 열지 않는다.

수용한 위험(IMAGE PR1 검토):

- 본문의 `products/media/…` 키는 이미 공개 확정된 미디어 객체라 업로더 소속을 보지 않는다. 이 상품 본문에 실려 있으면 후보로 인정한다.
- 초안을 만든 뒤 상품에서 뗀 이미지도 초안이 `REQUESTED`이고 7일 보관 안이며 해시가 같으면 생성에 쓰인다. 중단이 필요하면 플래그를 닫는다.
- `previewUrl`은 이미 고객에게 공개된 주소라 "어드민 응답에 스토리지 키 없음" 원칙의 예외다(공개 주소에 키 경로가 드러난다). 외부 주소는 싣지 않는다.

## 5. 검증·배포 경계

- 단위: 누락·이상 치수, 템플릿 매핑, 숫자·단위, 구조화 응답 거절, 개인정보 가림과 입력 차단.
- product DB: 멱등 키 경합, 초안·차트 revision, 중복 적용, 수동 편집 경합, 행·칸 전체 롤백과 실제 PostgreSQL DDL.
- operations DB: 완료 뒤 같은 draft 등록 재생, 중복 작업 억제, lease 만료·취소 경합, 실패 재시도와 job 완료 알림.
- 통합: 등록 응답 유실, 결과 조회 장애, 작업은 성공했으나 product가 일시 중단된 경우, 일괄 부분 실패와 재시도.
- 보안: ADMIN 역할·internal 허용 호출자, 이미지 소속·형식·용량, 로그·감사에 개인정보·바이트·서명 URL 없음.

새 엔티티와 revision 컬럼의 실제 운영 DDL은 PostgreSQL 17 생성 스냅숏으로 확인해 해당 PR 본문에 싣는다.
운영 DB에 additive DDL을 먼저 적용하고 모든 인스턴스를 호환 바이너리로 바꾼 뒤 기능을 연다.
메시징·AI 또는 필요한 내부 서비스가 꺼져 있으면 작업을 성공한 것처럼 응답하거나 초안을 자동 적용하지 않는다.
