# HANDOFF #571 검토용 법률 문서 AI 초안 생성 (2026-11-27 17:45)

브랜치 `feature/be/571-legal-draft-generation` (origin/develop 2faa4eb0 위). **PR 전에 이 파일을 지운다.**

## 현재 상태

- 커밋 1 `c42597f0` — 앞 세션 WIP(13:45 중단)를 이어받아 만든 기능 본체. 33파일 +1,283.
  - 메인 세션이 고친 것은 테스트 3건(`AiStructuredResult` usage null, claim attempt 1 → 0)과 스냅숏 누락이다.
- 커밋 2 (이 파일이 들어 있는 커밋) — 리뷰를 반영한 리팩터링의 중간 저장, `.env.example` 플래그, 이 파일.
- 마지막 검증(커밋 2, 17:26, be-executor 실행 — 다음 세션은 직접 다시 돌린다): 대상 테스트 83건 중 실패 1, skip 1. 캐시 복원이 아니라 실제로 실행했다.

  | 테스트 | 결과 |
  |---|---|
  | `LegalDraftGenerationFlowTest` | 39 통과 |
  | `OperationsAdminAuditProcessorTest` | 16 통과 |
  | `OperationsAdminAuthorizationMappingTest` | 1 통과 (경로 · 권한 불변) |
  | `OperationsSchemaSnapshotTest` | 3 통과 |
  | `OperationsServiceArchitectureTest` | 23 중 **H8 1건 실패** — 아래 "막힌 것" |
  | `LegalDraftGenerationPostgresTest` | 1 skip (로컬 Docker 없음, CI 확인) |

- 커밋 2 기준 operations 모듈 check 는 아직 돌리지 않았다.
- 커밋 1 기준 모듈 check 는 2,054건 중 실패 4건이었다. H1 · H8 과, 이 작업과 무관한 기존 `DltAlertFlowTest` 간헐 실패 2건이다.
- 리뷰(커밋 1 기준)
  - be-security: MEDIUM 이상 없음.
  - reviewer: APPROVE.
  - be-hexagonal: HIGH 2(H1 · H8), MEDIUM 2.
  - backend-api-check: MEDIUM 2.

## 확정한 결정

1. **AI 역할 범위 (a)** — 사용자가 확정했고 #571 댓글에 기록돼 있다.
   - 모델은 서버가 준 5개 절 ID 의 순서만 제안한다.
   - 본문은 서버가 시작 템플릿과 관리자가 확인한 사실로 조립한다. 프롬프트에 사실값은 넣지 않는다.
   - PR 본문에 "설계 선택"으로 명시한다.
2. 응답의 `status` · `candidate.type` 은 enum metadata(`{code, name, description}`)로 내보낸다. FE #572 계약이다.
3. 영수증에 사실값을 더 저장하지 않는다. 템플릿 버전이 바뀐 뒤 예전 결과를 조회하면 후보 없이 안내 문구만 준다.
4. 후속 후보 3개는 새 이슈로 만들지 않고 #571 댓글로 남겼다.
   - 공용 `/api/v1/admin/jobs` 로 남의 LEGAL_DRAFT 작업을 조회 · 취소할 수 있다.
   - 요청자별 진행 중 작업 수에 상한이 없다.
   - 저장된 초안과 AI 작업의 연결을 추적하지 않는다.

## 리팩터링 진행 (리뷰 반영)

| # | 항목 | 상태 |
|---|---|---|
| 1 | [HIGH · H8] 다른 컨텍스트 Processor 를 Facade 로 올린다 | **부분**: Registration · Result 는 끝났다. 새 `LegalDraftGenerationAdminFacade` 가 TransactionTemplate 으로 영수증과 작업 등록을 원자적으로 묶고, 경합이 나면 replay 한다. **`LegalDraftTemplateProcessor` 는 남았다** |
| 2 | [HIGH · H1] Controller 는 UseCase 만 쓰고 Presenter 는 Facade 가 조합한다 | 완료. 이름은 `LegalDraftGenerationAdminController` · `AdminUseCase` · `AdminPresenter` · `RegisterCommand` 로 바꿨다 |
| 3 | [MEDIUM] job 타입이 새는 곳 | 완료. Info 는 metadata 를 쓰고, Facade 의 JobException 분기는 작업을 다시 읽어 판정하는 방식으로 바꿨다. 어댑터는 `LegalDraftRemoteFailure` 를 던지고 작업 핸들러가 `JobExecutionException` 으로 옮긴다. Input 은 type · locale 원시값이다(영수증 `input_json` 모양이 바뀌었지만 배포 전이다) |
| 4 | [MEDIUM · FE 계약] status · type metadata, templateVersion Integer + null 이면 400 | 완료. FlowTest 에 400 케이스를 추가했다 |
| 5 | [MEDIUM] 감사를 `OperationsAdminAuditProcessor` · `OperationsAdminAuditAction` 3개로 옮긴다 | 완료. `LegalDraftAuditProcessor` 는 삭제했다 |
| 6 | [MEDIUM] 템플릿 버전이 바뀐 뒤 조회하면 503 이 나던 것을 후보 없음 + 안내 문구로 | 완료. 문서에도 적었다. **이 경우를 검증하는 테스트는 아직 없다** |
| 7 | [LOW] 정리 | 일부. 남은 것은 Gate 이름 · 위치(테스트가 SpyBean 으로 쓴다), `adapter/out/ai` → `adapter/out/client/ai`. 문서의 플래그 반영 시점은 "재기동 뒤 반영"으로 고쳤다 |

## 막힌 것 — H8 남은 1건 (조율 결정 필요)

- `LegalDraftTemplateProcessor` 가 legaldocument 의 `LegalDocumentTemplateProcessor` 와 Info(`LegalDocumentInfos.Template`)를 쓴다.
- H8 은 Facade 에게도 다른 컨텍스트 Info 를 허용하지 않는다. 그리고 작업 핸들러는 Facade 를 거치지 않고 job 엔진이 직접 부른다.
- 그래서 **legaldocument 코드를 바꿔야 고칠 수 있다.** 제안 순서:
  1. legaldocument `domain/model` 에 템플릿 값 `LegalDocumentTemplate`(templateId · version · type · locale · title · body · checklist · guidance)과 순수 정적 팩터리(예: `LegalDocumentTemplates.of(LegalDocumentKey)`)를 둔다. 템플릿은 DB 나 I/O 가 없는 상수다. 기존 `LegalDocumentTemplateProcessor` 는 이 값을 Info 로 감싸기만 하게 한다.
  2. `LegalDraftTemplateProcessor` 는 그 domain 팩터리를 직접 부른다. Processor 가 다른 컨텍스트 domain 을 쓰는 것은 허용된다.
  3. `Outline` 에는 자기 값(templateId · version · title · checklist)만 둔다.
- legaldocument 를 다른 세션이 작업 중인지 먼저 확인한다.

## 다음 할 일

1. 위 "막힌 것"을 조율 세션에 확인한 뒤 H8 을 해결한다.
2. 템플릿 버전 변경 시나리오 테스트를 추가한다.
3. 7번 LOW 정리 — 관례가 확인되는 것만 한다.
4. 180자를 넘는 줄이 있는지 확인한다(문자 수 기준). 후보는 `OperationsAdminAuditProcessor`:63, `LegalDraftGenerationAdminFacade`:27 · 100 · 147, `LegalDraftResultProcessor`:83, `LegalDraftGenerationResponse`:31, AdminController:56.
5. 대상 테스트 · ArchUnit 을 돌리고, operations 모듈 check 를 1회 돌린다(`--no-build-cache`).
6. be-hexagonal · reviewer 로 재검토받는다(응답 계약이 바뀌었으니 `/backend-api-check` 도).
7. 이 파일을 지우고 PR 을 만든다(`/pr`, `Refs #571`).
   - 브랜치에 중간 저장 커밋이 있다. 커밋을 정리하려면, 강제 push 없이 새 브랜치에 최종 파일로 커밋 1개를 만들어 올린다(#717 → #771 방식).
   - PR 본문에 적을 것:
     - AI 역할 범위 (a)
     - 실제 공급자 호출 · 비용은 검증하지 않음(stub 만)
     - 운영 DDL `operations.legal_draft_generation_receipt`
     - 플래그 `OPERATIONS_LEGAL_DRAFT_GENERATION_ENABLED`(기본 false)
     - FE #572 계약(status · type metadata, templateVersion 누락 400)
     - 후속 3개
     - 30파일 초과 사유(한 기능의 계층 전체, 약 42파일)
8. #477 · #785 와 operations `application.yml` 이 겹친다. 서로 다른 절이라 rebase 때 확인만 하면 된다.

## 검증 명령 (backend/ 에서)

```bash
./gradlew :service:operations-service:test --tests '*LegalDraft*' --tests '*OperationsServiceArchitectureTest' \
  --tests '*OperationsAdminAuditProcessorTest' --tests '*OperationsAdminAuthorizationMappingTest' --tests '*OperationsSchemaSnapshotTest'
./gradlew :service:operations-service:cleanTest :service:operations-service:check --no-build-cache
```

`--stop` 과 루트 check 는 금지다. `cleanTest` 만 해도 test 결과가 빌드 캐시에서 복원되므로 반복 실행에는 `--no-build-cache` 를 붙인다.

## 다음 세션 프롬프트

> #571 이어서. 브랜치 feature/be/571-legal-draft-generation 의 HANDOFF-571.md 를 먼저 읽는다. "막힌 것"(H8 — legaldocument 에 템플릿 domain 값 · 팩터리 추가)을 조율 세션에 확인한 뒤, /dev-orchestrator 로 남은 항목(H8, 템플릿 변경 테스트, LOW 정리)을 마친다. 이어서 대상 테스트 · ArchUnit · operations 모듈 check → be-hexagonal · reviewer · /backend-api-check 재검토 → HANDOFF 삭제 → /pr(Refs #571) 순서로 간다. AI 역할 범위는 (a) 로 확정됐다.
