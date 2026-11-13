# HANDOFF — #359 PR-2b-2 (operations 등록 · 영수증 · 결과 조회 · 플래그 · DDL)

> PR 을 올리기 전에 이 파일을 지운다(public 저장소). 남길 내용은 #359 이슈 댓글로 옮긴다.

## 상태 (2026-11-13 13:20, 세션 92)

- 2b-1 은 PR #720 으로 머지됐다(`4ddc445e`). 핸들러 `PRODUCT_DRAFT_GENERATE`(maxAttempts 2 · 동시 1)는 이미 develop 에 있다.
- 기준은 origin/develop `36fd2a47` 이다. 브랜치는 `feature/be/359-product-draft-generation-register`, 워크트리는 `commerce-foundry-wt/359-draft-gen-register` 다.
- 13:40 기준으로 **영속성 조각은 끝냈다**(WIP 커밋에 있다).
  - `domain/model/ProductDraftGenerationReceipt`: 회차 1~3, hash 는 64자 소문자 hex.
  - `adapter/out/persistence/entity/ProductDraftGenerationReceiptEntity`
    - `@IdClass(Key)` 로 (draft_id, generation_no) 복합 PK 를 둔다.
    - job_id UNIQUE, `@CheckConstraint` generation_no 1~3.
  - `...repository/ProductDraftGenerationReceiptRepository`: findByDraftIdAndGenerationNo · findByJobId.
  - `ProductDraftGenerationReceiptRepositoryAdapter`: persist + flush, 제약 위반은 DataIntegrityViolationException 으로 바꾼다.
  - `application/port/out/ProductDraftGenerationReceiptPort`
  - 테스트 `ProductDraftGenerationReceiptEntityMappingTest` 4건.
  - `src/test/resources/ddl/operations-schema.sql` 에 테이블을 더했다.
  - 플래그 `operations.product-draft-generation.enabled` 를 `application.yml` 과 `backend/.env.example` 에 더했다.
- 검증(13:32, 직접 실행): `--tests '*ProductDraftGenerationReceipt*' --tests '*LegalDocumentH2Test'` → 4 + 8건 통과, 실패 0.
  - LegalDocumentH2Test 는 operations-schema.sql 위에서 `ddl-auto=validate` 로 기동한다. 그래서 새 엔티티와 DDL 이 맞는지도 확인된다.
  - 모듈 check 와 Postgres 검증은 아직 안 돌렸다.
- **남은 것**(아래 대응표에서 영속성 줄을 뺀 나머지):
  - Controller · DTO · ExceptionHandler · Presenter
  - Command · ErrorCode · Exception · ValidationMessage
  - Info · UseCase
  - ReceiptProcessor · ResultProcessor · InternalFacade 와 그 테스트
  - RegistrationH2Test · PostgresTest
  - 운영 DDL 문서와 docs 갱신
- 참고: 사이즈표 영수증 DDL 에는 input_hash CHECK 가 없다. 그래서 이 테이블에도 넣지 않았고, hash 형식 검사는 도메인 record 에서만 한다. DB CHECK 를 더할지는 be-db-reviewer 검토 때 정한다.

## 범위

operations-service 와 backend/docs 만 바꾼다. order · payment · product-service 는 건드리지 않는다. product 에서 이 API 를 부르는 연결은 PR-3 에서 한다.

## 본보기: `domainlayer/sizechartgeneration` (#217)

사이즈표 생성이 같은 모양(product → operations internal 등록 → job → 결과 조회)이다. 아래 파일을 그대로 대응시켜 `productdraftgeneration` 에 만든다. 2b-1 에서 이미 만든 것(AI 어댑터 · Feign · JobProcessor · ValidationProcessor · Candidate · RemoteFailure)은 제외했다.

| sizechartgeneration | productdraftgeneration 에 만들 것 |
|---|---|
| `adapter/in/internal/controller/SizeChartGenerationInternalController` (+Test) | `ProductDraftGenerationInternalController` — `@RequestMapping("/internal/v1/product-draft-generation-jobs")`, `@PreAuthorize("hasAuthority('SERVICE_product-service')")`, `@Hidden`. `POST`(등록) · `GET /{jobId}/result` |
| `adapter/in/internal/dto/request/SizeChartGenerationRegisterRequest` | `ProductDraftGenerationRegisterRequest` — draftId · (inputHash, 아래 결정 1) · actorMemberId |
| `.../dto/response/*RegistrationResponse` · `*ResultResponse` | 같은 이름으로 |
| `adapter/in/internal/exception/*ExceptionHandler` | 같은 이름으로 |
| `adapter/in/internal/presenter/*InternalPresenter` | 같은 이름으로 |
| `adapter/out/persistence/entity/*ReceiptEntity` (+MappingTest) | `ProductDraftGenerationReceiptEntity`, 테이블 `product_draft_generation_receipt` |
| `.../repository/*ReceiptRepository` · `*ReceiptRepositoryAdapter` | 같은 이름으로 |
| `application/command/*RegisterCommand` | 같은 이름으로 |
| `application/exception/*ErrorCode` · `*Exception` · `*ValidationMessage` | 코드 접두어 `PRODUCT_DRAFT_GENERATION_` (001 NOT_FOUND · 002 INPUT_CONFLICT · 005 EXPIRED · 010 RESULT_EXPIRED · 100 INVALID_REQUEST · 501 UNAVAILABLE · 502 RESULT_UNAVAILABLE · 503 INVALID_RESULT). 쓰기 전에 `backend/docs` 의 에러 코드 표에 접두어가 겹치지 않는지 확인한다 |
| `application/info/*RegistrationInfo` · `*ResultInfo` | 같은 이름으로 |
| `application/port/in/*InternalUseCase` | 같은 이름으로 |
| `application/port/out/*ReceiptPort` | 같은 이름으로 |
| `application/service/processor/*ReceiptProcessor` · `*ResultProcessor` (+Test) | 같은 이름으로. 결과 JSON 상한 · 객체 검사 · 파서 진단을 응답에 넣지 않는 규칙을 그대로 따른다 |
| `application/service/*InternalFacade` (+Test) | 같은 이름으로. `@Value("${operations.product-draft-generation.enabled:false}")` — 신규 등록만 막고, 기존 영수증 재생과 결과 조회는 유지한다 |
| `*RegistrationH2Test` · `*RegistrationPostgresTest` | 같은 이름으로(동시 등록 · 영수증 재생 · 충돌) |
| `domain/model/*Receipt` | `ProductDraftGenerationReceipt` |

설정과 DDL:
- `application.yml` 의 `operations.size-chart-generation.enabled` 바로 아래에 `operations.product-draft-generation.enabled: ${PRODUCT_DRAFT_GENERATION_ENABLED:false}` 를 더한다. dev · local 은 사이즈표가 어떻게 하는지 보고 똑같이 맞춘다.
- `src/test/resources/ddl/operations-schema.sql` 에 영수증 테이블을 더한다. 운영 DDL 문서는 사이즈표 영수증 DDL 이 적힌 곳을 찾아 같은 형식으로 쓴다. 사용자가 손으로 적용한다.
- 문서: service-inventory · api-design-guide(internal API 표) · job-result-storage-guide · 에러 코드 표.

## 사용자 결정 (2026-11-13 13:12, 셋 다 추천안)

1. 영수증 PK 는 **`(draft_id, generation_no)`** 다.
   - product 가 회차를 보낸다. 2b-2 에서는 1~3 을 받는다.
   - 회차 상한은 DB CHECK `generation_no BETWEEN 1 AND 3` 으로 지킨다.
   - 같은 회차를 다시 요청하면 기존 영수증을 그대로 돌려준다(재생).
2. **input_hash 를 받는다.** 같은 (draft_id, generation_no) 인데 hash · 요청자 · 만료 시각이 다르면 409 `INPUT_CONFLICT` 다.
3. **expires_at 을 받는다.** 만료된 초안의 새 등록은 409 `EXPIRED` 다. 이미 있는 영수증을 재생하는 것은 사이즈표 규칙을 따른다.

## (기록용) 처음에 정리했던 선택지

1. **영수증 키.**
   - 사이즈표는 `draft_id` 가 PK 라서 초안 하나에 작업 하나다.
   - 그런데 PR-3 에서는 같은 초안을 최대 3회까지 재생성한다. 그러면 PK 가 `draft_id` 하나여서는 안 된다.
   - 후보는 두 가지다.
     - (a) PK `(draft_id, generation_no)`. product 가 회차를 보낸다.
     - (b) PK `job_id` 에 `(draft_id, input_hash)` UNIQUE.
   - 추천은 (a)다. 회차 상한을 DB 가 지키게 할 수 있다.
   - 2b-2 에서 미리 정해 두지 않으면, PR-3 에서 테이블을 다시 고쳐야 한다.
2. **input_hash 필요 여부.**
   - 2b-1 의 payload 는 `{draftId}` 하나다(#359 11-11 댓글 "input_hash 는 필요 없을 수 있다").
   - 다만 product 는 생성 요청 때 키와 ETag 를 고정한다. 그래서 재요청이 같은 입력인지 가리려면 hash 가 쓸모 있다.
   - 결정 1과 묶어서 정한다.
3. **expires_at.** 사이즈표처럼 초안 만료 시각(7일, PR-1)을 받아 만료된 초안은 등록을 막을지 정한다. 추천은 막는 것(005)이다.

## 진행 순서

1. 결정 1~3 은 위 "사용자 결정" 에 적었다. 다시 묻지 않는다.
   - 요청 DTO 는 `draftId · generationNo · inputHash · actorMemberId · expiresAt` 이다.
   - job payload 는 2b-1 계약 `{draftId}` 를 그대로 둔다. 회차는 영수증에만 둔다.
2. `/dev-orchestrator` 로 분류한다(FEATURE, 영속성 · internal API · 플래그 → T2). be-executor 한 번에 위 표를 구현한다.
3. 반복 중에는 바꾼 클래스만 돈다: `./gradlew :service:operations-service:test --tests '*ProductDraftGeneration*'`.
4. PR 직전에 `:service:operations-service:cleanTest :service:operations-service:check --no-build-cache` 를 돌리고, XML 로 집계한다.
   - 메모리 부족으로 중단되면 사용자에게 묻는다(11-13 에 CI 로 대체한 적 있다).
   - 알려진 간헐 실패: `JobWorkerFlowTest`(#717) · `DltAlertFlowTest` · `ShippingPolicyFactClientTest`.
5. 검토는 병렬로 돌린다: be-security-reviewer(internal API) · be-hexagonal-reviewer · be-db-reviewer(영수증 테이블).
6. 이 HANDOFF 파일을 지우고, 코드와 문서 커밋을 나눈다. 그다음 `/pr` 을 부르면서 검증 결과를 인자로 넘긴다. 파일이 30개를 넘으면 사유를 적는다(예상 31~33).

## 붙여 넣을 프롬프트

```
#359 PR-2b-2 를 이어서 한다. 워크트리 D:/ProjectWorkSpace/commerce-foundry-wt/359-draft-gen-register
(브랜치 feature/be/359-product-draft-generation-register) 의 HANDOFF-359-2b-2.md 를 먼저 읽고,
"사용자 결정" 1~3(확정)을 따라 /dev-orchestrator 로 구현 → 검증 → 검토 → /pr 까지 간다.
범위는 operations-service + backend/docs 만. 단계마다 [n/7 ...] 한글 보고.
```
