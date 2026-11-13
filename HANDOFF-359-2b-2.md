# HANDOFF — #359 PR-2b-2 (operations 등록 · 영수증 · 결과 조회 · 플래그 · DDL)

> PR 을 올리기 전에 이 파일을 지운다(public 저장소). 남길 내용은 #359 이슈 댓글로 옮긴다.

## 상태 (2026-11-13 13:20, 세션 92)

- 2b-1 은 PR #720 으로 머지됐다(`4ddc445e`). 핸들러 `PRODUCT_DRAFT_GENERATE`(maxAttempts 2 · 동시 1)는 이미 develop 에 있다.
- 2b-2 는 **코드 작성 전**이다. 이 브랜치에는 이 HANDOFF 파일만 있다. 기준은 origin/develop `36fd2a47` 이다.
- 브랜치는 `feature/be/359-product-draft-generation-register`, 워크트리는 `commerce-foundry-wt/359-draft-gen-register` 다.
- 검증: 아직 돌린 것이 없다.

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

## 먼저 정할 것 (구현 전에 사용자에게 묻는다)

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

1. 위 결정 1~3 을 사용자에게 묻는다(AskUserQuestion, 추천 표시).
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
"먼저 정할 것" 1~3 을 나에게 물은 뒤 /dev-orchestrator 로 구현 → 검증 → 검토 → /pr 까지 간다.
범위는 operations-service + backend/docs 만. 단계마다 [n/7 ...] 한글 보고.
```
