# #20 USD B1b S1 재개 인계 — 2026-11-06

## 현재 상태와 재개 경계

- 이 문서는 구현 완료 보고가 아니라 확정 설계와 미완 작업의 인계다. TYPE `ARCHITECTURE`, COMPLEXITY `T3`: 스키마 호환·부모 CAS 동시성·기존 계약 보존.
- 사용자 배정: S1, thread `01a1afd9-075f-79e2-8eac-5eae2ca820f9`. 감독: `01a1a3f5-4de4-7731-86ca-8d465142cd9b`.
- 설계 대조 기준 `origin/develop=d6f20e36cc756a150b00c95c10641b0e2d5337cf`.
- 예정/인계 브랜치 `feature/be/20-usd-legacy-price-writers`, WT `D:/ProjectWorkSpace/commerce-foundry-wt/20-usd-legacy-price-writers`.
- 오늘 17:30 감독 마감 지시에 따라 큰 새 구현과 B 착수를 중단하고 설계를 커밋·푸시한다. production 코드·엔티티·기존 writer 변경은 없다.
- A는 caller 없는 계획/조율기 기반으로 분리한다. A도 아직 구현하지 않았다. B의 5 writer 연결, B1c 전역 이행·정본 전환과 #20 전체는 미완료다.
- 저장소 `dev-orchestrator`와 `pr`를 매 작업 적용하고 한국어로 진행·감독 보고한다. 사용자만 머지한다.

## 소유·제외 범위

product-service 가격 writer·관련 시험·최소 USD 문서만 소유한다. Claude의 order/claim·auth/운영자 권한 및 payment/core를 수정하지 않는다.
B1c 유지보수 CLI/backfill/정본 전환, ADMIN 통화 편집, 공개 USD 조회, cart/order/구매 gate는 이번 범위 밖이다.
루트 develop의 AGENTS.md/docs/git-workflow.md dirty와 타 세션 WT/브랜치, 보호 WIP는 보존했다.

## 최신 코드와 설계 대조

기존 로컬 설계 `reports/20-usd-b1b-design.md`는 B1a merge `d2102534861e929ca9ac6eb81f570e4fcc5aef3e` 기준이었다.
architect·DB 역할이 최신 develop의 5 writer·PriceBook·저장 어댑터가 기존 설계 이후 바뀌지 않았음을 확인했다.
B1a 이후 product 변경은 ADMIN 권한과 관련 시험 22파일(+543/-62)이다. 현재 인가는 `AdminAuthority.CATALOG_MANAGE`를 기준으로 하며 구 ADMIN 전용 시험을 복원하지 않는다.

- 기준통화는 legacy 판매가의 실제 통화다. KRW로 추정하지 않는다. STOP·은퇴를 포함한 전체 SKU 기준행이 필수다.
- 추가 통화 partial/미지원과 실제 0을 구분한다. 지원 ON_SALE SKU가 없으면 zero 조건을 요구하지 않는 현행 정책을 유지한다.
- PriceBookRepositoryPort의 상품/SKU 일괄조회와 insertAll/updateAll 네 메서드를 재사용한다. updateAll의 ID별 추가 벌크 조회와 prepare의 종류별 각1회 조회를 구분한다.
- 같은 product 행의 CAS/@Version을 기존 writer가 확보한다. MANDATORY만으로 선행 부모 보호를 증명할 수는 없다.
- 전역 Product adapter hook·새 재시도·독립 commit·가격/재고 잠금은 추가하지 않는다.

## A 최소 설계 — 미구현

`LegacyPriceSnapshot`, `LegacyPriceWritePlan`, `LegacyPriceWriteProcessor`를 같은 product 컨텍스트에 둔다.
Snapshot은 productId/판매가/nullable 정가/전체 SKU의 불변 복사본이며 소속·중복 ID를 검사한다. 재고는 조회하지 않는다.
Plan은 상품/SKU insert 목록과 실제 달라진 기존 기준행 update 목록만 갖는다. 추가 통화는 검증에 참여하지만 쓰기 목록에 넣지 않는다.
ID는 application의 Snowflake 발급을 사용한다. LongSupplier를 순수 계획 생성 인자로 쓸 수 있으나 완성 Plan에 보관하지 않는다. 누락행마다 한 번 발급하여 before 보완과 최종 insert에 같은 ID를 쓴다.

Processor의 `prepare(before, after)`, `prepareNew(after)`, `write(plan)`는 모두 MANDATORY다.
부모 보호를 유지한 동일 TX 안에서 준비·저장하며 계획을 캐시하거나 다음 TX로 넘기지 않는다.
A에는 기존 writer/listener/scheduler/bootstrap 등 production caller를 연결하지 않고 생성자 부작용도 두지 않는다.

prepare 순서:

1. 상품·불변 기준통화·SKU 소속을 확인한다. 기존 SKU 제거는 허용하지 않는다.
2. 상품가격 전체를 한 번, before∪after SKU ID의 가격 전체를 한 번 조회한다.
3. 기존 기준행과 legacy before의 금액을 비교한다. 새 SKU ID의 예상 밖 가격행은 통화에 관계없이 거절한다.
4. 누락 기준행만 before 값으로 메모리 보완하고 before PriceBook을 검증한다.
5. after 가격과 전체 SKU 상태로 추가 통화까지 PriceBook을 검증한다.
6. 누락행은 최종 after 값으로 한 번 insert하고 기존 기준행은 실제 금액 변경만 update한다. 추가 통화의 값·ID·createdAt은 보존한다.

prepareNew는 before 집합 없이 신규 상품·SKU ID의 예상 밖 기존 행을 먼저 거절하고 전체 기준행 insert 계획을 만든다.

## 오류·트랜잭션 계약

| 상황 | 처리 |
|---|---|
| 기존 입력/membership/은퇴 옵션 검증 | 기존 코드·검사 순서 보존 |
| 부모 경쟁 | PRODUCT_010 또는 SKU_ADMIN_002 409 |
| 기존 drift·소속/중복·부모 없는 추가 통화·before 불변식 오류 | 정제 IllegalStateException → COMMON_000 500, 전체 rollback |
| 유효 before에 요청을 적용한 after 가격규칙 위반 | 전용 domain 요청규칙 예외 → writer가 PRODUCT_016 또는 SKU_ADMIN_004로 변환 |
| 가격 저장 제약/identity 실패 | 공통 500, 전체 rollback |
| 잠금·쿼리·TX timeout | 기존 Facade 분류, 특히 SKU_ADMIN_005 보존 |

before와 after를 한 넓은 IllegalArgumentException catch로 묶지 않는다. PriceBook의 Money 합산 ArithmeticException도 단계별로 구분한다.
상품 ID 변경·SKU 제거·ID 생성 실패 같은 내부 계약 오류를 요청 400으로 번역하지 않는다. SQL·금액 원문을 진단 로그에 남기지 않는다.
가격 insert 뒤 update 실패도 같은 TX 전체를 롤백한다. 커밋 전 상세/Presenter 실패는 기존 TX 롤백, 등록의 커밋 후 응답 실패는 저장 유지다.

## B 연결 위치 — 오늘 착수 금지

| writer | 순서 |
|---|---|
| 신규 등록 ProductWriteProcessor | 기존 product/options/SKU 저장 → flushNewProduct → prepareNew/write → 초기 재고·이력 → 기존 후속 처리 |
| ProductPricingUpdateProcessor | 기존 존재·버전·통화·가격 검사 → 실제 변경만 before 확보 → 부모 saveAndFlush → prepare/write → 상세 |
| SkuAddProcessor | CAS/clear → fresh before·기존 검증 → 옵션/SKU insert → flushClassified → catch 밖 prepare/write → 초기 재고·이력 |
| SkuBulkUpdateProcessor | CAS/clear → fresh before·membership/가격/은퇴 검증 → prepare → 기존 SKU ID 오름차순 저장·flush → write |
| OptionRetirementProcessor | 기존 검사·기은퇴 no-op → CAS/clear → fresh before·after 전체 상태/가격검증 → prepare → 옵션/SKU 변경 → write |

가격 동일값·기은퇴 no-op은 가격 조회/ID발급/추가 CAS에 진입하지 않는다. SKU 전체 편집은 동일값이어도 기존 버전 증가를 유지한다.
가격 엔티티를 분류 flush 전에 pending/dirty로 만들면 옵션 오류 SKU_ADMIN_007 또는 재고 오류로 오역될 수 있으므로 금지한다.

## 필수 검증 — 아직 실행하지 않음

A: target 없음/일부 누락/완전 기준행, KRW·USD 기준, drift·잘못된 소속·중복·새 ID의 예상 밖 행, 부분 추가 통화/지원SKU0개 현행 허용, zero/정가null/overflow, ID재사용·실제변경 update, prepare 무쓰기·MANDATORY, insert후update 실패 실제 TX rollback.
B: 5 writer 연결·no-op·버전·오류 우선순위·추가통화 마지막zero 은퇴거절·ID/createdAt·감사·재고/원가/선점 보존·기존 응답·v1 지문 회귀.
B 실제 PG: 가격↔가격/추가/전체편집/은퇴, 승리한 가격을 stale 헤더/삭제/맞춤주문 merge가 덮지 않음과 반대 방향, SKU잠금 timeout·부모rollback·stock 비간섭, 가격실패와 재고/이력 저장 뒤 후속실패의 전체 원자성.
조회 계수는 prepare 2회와 updateAll 재조회를 구분하고 SKU 규모를 늘려 N+1 부재를 확인한다. prepared 문 수를 DB 왕복 수로 단정하지 않는다.

구현 후 메인이 공용 build.lock을 원자 획득하고 대상 시험 → 변경 product 모듈 검증을 직접 실행한다. 기존 빌드와 잠금 소유자를 확인하고 소유자만 finally에서 해제한다.

```powershell
cd backend
.\gradlew.bat :service:product-service:test --tests '*LegacyPrice*' --no-build-cache
.\gradlew.bat :service:product-service:cleanTest :service:product-service:check --no-build-cache
```

이번 세션에서는 위 빌드/테스트를 실행하지 않았다. 로컬 docker 명령과 표준 설치 경로/실행 프로세스를 찾지 못했다. 실제 PG 검증 미완료이며 skip을 통과로 보고하지 않는다. CI의 실제 실행/실패/오류/skip 건수를 대조한다.
root check/test, gradlew --stop, 일반 --no-daemon은 금지한다. product 외 테스트는 CI 근거로 구분한다.

## 규모·배포·다음 실행

단일 PR 초기 예상1070~1540줄, 분리 후 A600~900/B700~1000줄 목표(분리비용 포함 합산1300~1900줄)다. 이는 확정 diff가 아니라 기존 시험/호출점 기반 추정이다.
기존 시험 길이는 storage188/가격246/SKU추가204/전체편집365/은퇴172줄이다. 필수 시험을 줄여 크기를 맞추지 않는다.
실제 30파일/1000줄을 넘으면 정확 총량·분리 대안/예외 사유를 먼저 감독에게 보고한다. writer 일부 활성화 분리는 금지한다.

다음 세션은 branch를 fetch/checkout하고 AGENTS/backend AGENTS·dev-orchestrator/pr·본 문서·#20 최신 본문과 PR/WT 소유권을 대조한다.
A 구현·직접 검증·DB/계층/관련 전문 및 최종 리뷰 후 develop PR로 게시한다. A 머지 후 별도 착수 지시에 따라 B에서 다섯 writer를 함께 연결한다.
B 배포는 쓰기 중지·진행 TX drain → 모든 인스턴스 호환 binary 교체 → legacy smoke → 재개를 전제로 한다.
B 활성화 뒤 A를 포함한 legacy-only binary로 복귀하여 쓰기를 재개하면 안 된다. 호환 writer를 유지하는 binary로만 복귀한다.
운영 DDL 적용/직접 SQL writer/실제 중단창·복귀 binary는 미확인이다. 엔티티 변경이 없다면 신규 DDL이 필요하지 않으나 B1a DDL 적용 여부는 배포 전에 확인한다.
#20은 OPEN 유지하고 B1b/B1c 또는 USD 구매 완료로 표시하지 않는다.
