# 외부 쇼핑몰 데이터 이관 (카페24, #19)

## 1. 목적과 범위

- 운영 중인 외부 쇼핑몰(첫 고객사는 카페24)의 **상품 · 옵션 · 이미지 · 회원**을 이 엔진으로 옮기는 도구의 정본이다.
- 재사용 가능한 엔진이라 소스(카페24)별 규칙은 `migration` 컨텍스트의 소스 하위(`cafe24`)와 설정(`migration.*`)에 둔다. 브랜드 규칙을 공통 코드에 고정하지 않는다.
- **이관하지 않는 것(전제 — 결정 D11 확인 대상)**: 카페24 주문 이력 · 적립금 · 회원 등급 · 배송지 · 게시판.
- 이 문서는 #19 첫 PR 기준이다. 결정 대기(§3) 항목이 정해지면 이 문서를 먼저 고치고 구현한다.

## 2. 파이프라인 (권고안 — D6 확인 대상)

실행 위치는 operations-service 의 백그라운드 작업(`architecture-guide.md` §1 · §10, `api-design-guide.md` §7)이다. 어드민이 내보내기 파일을 올리고 작업을 등록하면
워커가 읽기 → 검증 → 이미지 이전 → 대상 서비스 internal 호출 순서로 처리한다.

```text
카페24 내보내기(CSV) ─▶ 원본 행(SourceRow) ─▶ 정규화 레코드(Source*Record) + 이슈(MigrationIssue)
                                                    │  1단계 전체 검증 — 쓰기 없음, 리포트만
                                                    ▼
                                    이미지 내려받기 · 스토리지 put ─▶ 이관 명령 ─▶ product / auth internal 호출
                                                    ▼
                                    원본 키 → 새 아이디 매핑 저장 ─▶ 대조 리포트
```

- **3단 표현** — 원본 행(`SourceRow`, 문자열 그대로 · 행 번호) → 정규화 레코드(`SourceProductRecord` 등, 이 엔진의 값 규칙을 지킨 값) → 이관 명령(대상 서비스 요청 1건).
- **정규화 실패는 예외가 아니라 이슈로 쌓는다** — `MigrationIssue(rowNumber, sourceKey, field, code)`. **셀 값을 싣지 않는다**(§6).
- 1단계 검증에서 거절 이슈가 있으면 쓰기를 하지 않는다(운영자가 원본을 고쳐 다시 올린다). 계속 진행 옵션은 설정으로 둔다.
- 엔진(#212)에 의존하지 않는 부분 — 읽기 · 정규화 · 매핑 — 은 순수 Java 로 두어 엔진 머지 전에도 컴파일 · 테스트된다.
  작업 핸들러 · 대상 서비스 호출 · 스토리지 어댑터는 #212 머지 뒤 붙인다.

## 3. 결정 대기

| # | 항목 | 권고 | 막는 범위 |
|---|------|------|-----------|
| D1 | 회원 비밀번호(이관 불가) 뒤 로그인 복구 | 이관 회원은 쓸 수 없는 해시(회원마다 난수)로 넣고, 컷오버 안내 메일에 재설정 링크. 로그인 실패 문구는 `security-guide.md` §10 대로 일반 문구 유지, 화면에 상시 재설정. 소셜 자동 연결 금지(카페24 이메일은 소유 검증 값이 아니다) | 회원 이관 전체. **선행: 비밀번호 재설정 · 이메일 발송 기능(auth 에 없음)** |
| D2 | 개인정보 이전 근거 · 동의 (법률 자문 아님) | 사업자 동일 여부 확인. 필수 동의 이력을 `terms_version = migrated-<날짜>` 로 남기고 첫 로그인 때 현행 약관 재동의. 마케팅은 원본에 수신동의 칸 · 일시가 있을 때만 true | 회원 이관 + FE 재동의 화면 |
| D3 | 내보내기 형식 | 관리자 화면 CSV(엑셀) 다운로드. 인코딩(CP949 가능성) · 상세 HTML 포함 여부 · 실제 컬럼명 확인 필요. xlsx 만 있으면 운영자가 "CSV UTF-8" 로 변환 | 컬럼 매핑 확정(지금은 설정값 = 가정) |
| D4 | 옵션 → SKU | 조합형 그대로. 독립형은 그룹 1개면 같게, 2개 이상 거절 + 리포트. 음수 옵션가는 기본가 = 최저 SKU 가(§5). 수동 품절은 재고 0, 판매안함은 `STOP_SELLING`. 그룹 6개 이상 · 추가구성상품 거절 | 옵션 파싱(이 PR 범위 밖) |
| D5 | 이미지 이전 | 도구가 카페24 CDN 에서 받아 형식(magic bytes) 검증 후 `products/migrated/{sha256}.{ext}` 에 put(재실행은 `head` 로 건너뜀). 상세 HTML 의 이미지 URL 치환 → product 쓰기 경계에서 허용 목록 정제 순서. GIF 등 형식 밖 · 10MB 초과 거절 | 이미지 단계 |
| D6 | 실행 위치 | operations-service 작업 핸들러(§2). 별도 CLI 는 사설망 internal API · 서비스 토큰 비밀을 운영자 PC 로 반출해야 해 제외 | 읽기 · 정규화 다음 단계 전부 |
| D7 | 등록일 순서 보존 | 원본 등록일 오름차순 순차 등록 — Snowflake 발급 순서가 정렬(`NEWEST`) 순서가 된다. 스키마 변경 없음. 원본 등록일 표시가 필요하면 `registered_at` 을 별도 이슈로 | 실행 순서 |
| D8 | 카테고리 | 어드민이 미리 만들고 매핑 파일(카페24 분류코드 → categoryId)을 작업 입력으로. 매핑 없는 상품 거절 | 상품 이관 |
| D9 | 301 URL 매핑(FE) | 매핑 정본은 operations 테이블, CSV 로 내보내 FE `next.config` redirects 생성. 카페24 URL 형태 · 새 경로 패턴은 FE 확인 | FE 301 |
| D10 | 리허설 · 대조 리포트 | dev 에서 비우고 재실행 2회 이상, 최종은 staging(없으면 운영 DDL 로 맞춘 dev). 리포트는 건수 · SKU 가격 대조 · 거절 목록(행 번호 · 사유 코드, 값 없음) | 완료 기준 판정 |
| D11 | 컷오버 | 프리즈 창 — 카페24 주문 중단 → 변경분 재내보내기 → 업서트 재실행 → 재고 최종 덮어쓰기(고라이브 전 1회만) → DNS · 301. 이중 운영 안 함 | 실행 계획 |
| D12 | 회원 식별 · 중복 | 이메일 없음 거절, 소문자 정규화 뒤 중복은 첫 행만. 국내 휴대폰 규칙 밖 번호는 null + 리포트 | 회원 이관 |

## 4. 위치와 구조 (확정)

- operations-service `com.commercefoundry.domainlayer.migration` — 컨텍스트 이름은 소스 중립 `migration`, 소스 고유 규칙은 `cafe24` 이름을 단 클래스 · 설정에 둔다.
- `domain/model` — 원본 행 · 정규화 레코드 · 이슈 · 정규화 규칙(순수 Java, 스프링 의존 없음).
- `application/port/out/MigrationSourceReaderPort` — 내보내기 파일을 원본 행으로 읽는다. 구현은 `adapter/out/file/CsvMigrationSourceReader`(Apache Commons CSV).
- 컬럼 이름은 코드에 고정하지 않고 레코드(`Cafe24ProductColumns`)로 받는다 — 실제 이름은 D3 확인 뒤 설정(`migration.cafe24.columns.*`)으로 묶는다.

## 5. 정규화 규칙 (확정)

| 칸 | 규칙 | 실패 |
|----|------|------|
| 상품명 | 앞뒤 공백 제거 뒤 코드 포인트 1~100(`product.name varchar(100)`). 제어 · 서식 문자(`\p{Cc}` · `\p{Cf}`) 거절. 자동으로 자르지 않는다 | `NAME_REQUIRED` · `NAME_TOO_LONG` · `NAME_INVALID_CHARACTER` (거절) |
| 금액(KRW) | 쉼표 · 공백 · `원` 을 뺀 0 이상 정수. 소수 · 음수 · 숫자 아님 거절. USD 가격은 범위 밖 | `PRICE_REQUIRED` · `PRICE_INVALID` (거절) |
| 옵션가 | SKU 판매가 중 최저를 기본가로, 추가금 = SKU 판매가 − 기본가(0 이상). 음수 추가금이 있던 상품도 같은 규칙으로 바뀐다(#8 메모). 정가는 기본가 이상일 때만 남기고 아니면 비운다 | — |
| HS 코드 | `.` · `-` · 공백을 뺀 숫자 6~10자리(`ck_sku_hs_code_format`). 아니면 비우고 경고 | `HS_CODE_INVALID` (경고) |
| 무게 | 그램 · 킬로그램(단위는 설정)을 그램 정수로 반올림. 0 이하 · 빈 값은 비운다(`ck_sku_weight_grams_positive`) | `WEIGHT_INVALID` (경고) |
| 이메일 | 앞뒤 공백 제거 · 소문자 · 254자 이하 · `@` 하나와 점 있는 도메인 | `EMAIL_REQUIRED` · `EMAIL_INVALID` (거절) |

- 거절(`REJECT`)은 그 행을 이관하지 않는다. 경고(`WARN`)는 그 칸만 비우고 이관한다 — 이관 뒤 어드민이 채운다.
- SKU 옵션 키(`SkuOptionKey`)는 도구가 만들지 않는다 — 옵션 값 **이름**만 보내고 product-service 가 만든다("키를 만들고 읽는 곳은 이 클래스뿐").
- 상세 HTML 은 도구가 정제하지 않는다 — 이미지 URL 치환만 하고, 정제는 product 쓰기 경계의 허용 목록 sanitizer(#9 와 같은 클래스)가 한다.

## 6. 실데이터 · 개인정보 취급 (필수)

- **원본 내보내기 파일 · 실데이터 · 실데이터에서 뽑은 샘플을 저장소 · 이슈 · PR · 테스트 픽스처에 넣지 않는다** (공개 저장소).
  원본은 로컬 `backend/data/`(git 무시) 또는 운영 스토리지 비공개 prefix 에만 둔다. `*.csv` · `*.xlsx` 는 저장소 전역에서 무시된다.
- 테스트 데이터는 Java text block 으로 만든 **합성 값**만 쓴다 — 이메일 `@example.com`, 전화 `010-0000-xxxx`, 상품코드 `P0000000N` 꼴.
- 이슈 · 리포트 · 진행 문구 · 작업 결과 · 로그 · 예외 메시지에 **셀 값을 싣지 않는다** — 행 번호 · 원본 키(상품코드) · 칸 이름 · 사유 코드만.
  회원 행의 원본 키는 이메일이 아니라 행 번호다. `SourceRow` · 레코드의 `toString` 은 값을 내지 않는다.

## 7. 입력 계약 초안 (구현 전 — 대상 서비스 PR 에서 확정)

product-service 쓰기 경로는 #9 가 만들고 있어 이 계약은 문서로만 둔다. 구현은 #9 와 조율해 별도 PR 로 한다.

- product-service `POST /internal/v1/product-imports` — `hasAuthority('SERVICE_operations-service')`, 한 번에 100건 이하(`api-design-guide.md` §1-3), 항목 순서대로 처리(D7).
  항목: `sourceSystem` · `sourceKey`(멱등 업무 키) · `categoryId` · `name` · `descriptionHtml`(이미지 치환 완료, 정제는 product) · `salePrice` · `listPrice` ·
  `saleStatus` · `visible` · `optionGroups[{name, values[]}]`(5개 이하) · `skus[{optionValueNames[], additionalPrice, saleStatus, stock{managed, quantity}, weightGrams, hsCode}]` ·
  `images[{type, url, displayOrder}]`. 응답 항목: `sourceKey` · `productId` · `result(CREATED|UPDATED|UNCHANGED)` · SKU 아이디.
  같은 `(sourceSystem, sourceKey)` 재전송은 처음 결과와 같은 모양의 200 이다 — 응답이 유실돼도 다시 보내 아이디를 되찾는다.
- auth-service `POST /internal/v1/member-imports` — 같은 권한. 항목: `sourceKey` · `email` · `name` · `phone` · `registeredAt` · 동의 3종 · `termsVersion`.
  같은 이메일이 있으면 `EXISTS`(덮어쓰지 않는다). 배송지는 받지 않는다. 비밀번호는 받지 않는다(D1).

## 8. 이후 PR

| PR | 내용 | 선행 |
|----|------|------|
| 1 (이 PR) | 이 문서 · 원본 읽기 · 기본 칸 정규화 · 상품 기본 칸 매핑 골격 | — |
| 2 | product internal import API · 업무 키 테이블 · sanitizer 자리 | #9 와 조율 |
| 3 | auth internal member import · 쓸 수 없는 해시 | D1 · D2, 비밀번호 재설정 이슈 |
| 4 | 옵션 → SKU 파싱 · 작업 핸들러 · Feign · 스토리지 어댑터 · 매핑 테이블 · 어드민 등록 API | #212 머지, D3 · D4 · D5 · D8 |
| 5 | 회원 핸들러 · 대조 리포트 · 301 내보내기 | D9 · D10, #212 결과 파일 |
