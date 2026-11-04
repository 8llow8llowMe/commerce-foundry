# PUT · PATCH 계약 점검 (#561)

기준: `origin/develop`의 `bbcee13a`, 실제 서비스 Controller의 PUT 매핑 15개.
요청 DTO의 필수 여부와 저장 동작을 함께 확인했다. 수정 화면이라는 이유만으로 메서드를 바꾸지 않는다.
전체 교체에는 PUT, 기존 값에 변경 지시를 병합하는 계약에는 PATCH를 사용한다.
부분 리소스(`/pricing` 등)도 해당 리소스 전체를 교체하면 PUT이 적절하다.
스토리지 presigned PUT과 테스트 fixture는 이 목록에서 제외했다.

## 판단

아래 경로는 모두 `/api/v1` 접두어다.

| 서비스 | PUT 경로 | 판단 · 근거 |
|---|---|---|
| auth | `/members/me/shipping-addresses/{addressId}` | 유지. `ShippingAddressProcessor`가 주소 내용을 전체 교체하고 누락된 선택 칸을 지운다. 기본 여부는 별도 리소스다. |
| auth | `/members/me/shipping-addresses/{addressId}/default` | 유지. 해당 주소를 기본으로 지정하는 하위 리소스 설정이며 이미 기본이면 성공한다. 메서드만 바꿀 실익이 작다. |
| delivery | `/admin/shipping-policies/domestic` | 유지. `ShippingPolicyReplaceRequest`로 정책 값과 지역 목록 전체 교체. |
| delivery | `/admin/shipping-policies/international` | 유지. `InternationalShippingPolicyRepositoryAdapter`가 구역·국가·요율 집합을 교체한다. |
| product | `/admin/categories/{categoryId}` | 유지. `CategoryUpsertRequest`로 상위·이름·순서·노출 전체 교체. |
| product | `/admin/products/{productId}/pricing` | 유지. 판매가·정가 전체 교체. 정가 누락/null은 삭제다. |
| product | `/admin/products/{productId}/categories` | 유지. 대표·진열 카테고리 집합 전체 교체. |
| product | `/admin/products/{productId}/header` | PATCH 후보, 이번에는 보류. 국문 기본 칸은 교체하지만 영문 칸은 누락/null이면 기존 값 유지라 혼합 계약이다. 상품 이미지·수정 작업 PR #560 및 다국어 #20과 조율 후 별도 이슈에서 결정한다. |
| product | `/admin/stocks/{skuId}/low-stock-policy` | 유지. 기준 수량 한 값으로 정책 설정/교체. 실제 재고 변경 아님. |
| product | `/admin/displays/sections/{sectionId}` | 유지. 섹션 내용·아이템 전체 교체. |
| product | `/admin/looks/{lookId}` | 유지. 룩 내용·이미지·구성 상품 전체 교체. |
| product | `/admin/size-chart-templates/{templateId}` | 유지. 템플릿·열 전체 교체. |
| product | `/admin/products/{productId}/size-chart` | 유지. 차트 행·셀 전체 교체, 없으면 생성. |
| support | `/admin/support/knowledge/{documentId}` | 유지. 지정 리비전의 초안 콘텐츠 전체 편집. |
| operations | `/admin/onboarding/me/tours/{guideKey}` | PATCH 추가. `TourProgress.merge`가 같은 안내 버전의 상태 역행을 막고 단계 최댓값을 보존한다. 요청 표현으로 교체하는 계약이 아니다. |

## 온보딩 전환

- 정식 메서드는 `PATCH /api/v1/admin/onboarding/me/tours/{guideKey}`다.
- 기존 PUT은 deprecated 호환 별칭으로 남긴다. 삭제 일정은 FE 전환 확인 후 별도로 정한다.
- 두 메서드는 같은 ADMIN 인가·DTO·UseCase·응답 봉투·오류 코드를 사용한다.
- 본문은 기존 `{guideVersion, status, currentStep?}` 그대로다. `guideVersion`과 `status`는 필수다.
  업무 규칙에 따른 병합이며 `application/json`을 받는다. JSON Merge Patch 형식이나 임의 필드 수정 계약이 아니다.
- 같은 버전에서는 `currentStep` 누락/null이 기존 단계를 지우지 않는다. 낮은 단계 요청도 역행시키지 않는다.
  완료·건너뜀 상태 전이 규칙, 새 안내 버전 교체, `NOT_STARTED` 재실행 초기화는 기존과 같다.
- FE 변경 위치는 `frontend/apps/admin/src/lib/tour/onboarding-api.ts`의 `method`다.
  BE 배포 뒤 PUT을 PATCH로 변경할 수 있다. 기존 PUT 클라이언트도 계속 동작한다.

새 PATCH에 대한 인증·검증·응답·오류 계약과 PUT 호환은 `OnboardingAdminControllerTest`로 검증한다.
병합·재실행 규칙은 기존 `TourProgressTest`와 `OnboardingTourProcessorTest`를 포함한 모듈 check로 검증한다.

## 후속 보완 — 옵션 번역 호환 (#20)

`PUT /api/v1/admin/products/{productId}/options`는 한국어 이름·전체 ID 집합·순서를 교체하면서 선택적 `nameEn`만 미지정/null 보존을 허용한다.
영문 빈 값은 삭제한다. 기존 FE의 한국어 편집이 번역을 지우지 않도록 둔 PUT 호환 예외이며, 위 기존 15개 경로 판단은 바꾸지 않는다.
번역만 바뀌어도 기존 상품 버전 CAS·감사·전체 응답을 재사용한다. 메서드 전환·새 PATCH와 FE 번역 UI는 후속 범위다.
