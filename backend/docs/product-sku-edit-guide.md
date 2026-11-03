# 상품 SKU 편집 (#9)

`PUT /api/v1/admin/products/{productId}/skus`는 ADMIN access JWT의 memberId를 행위자로 사용한다.
기존 SKU 전체의 추가금·판매 상태·배송 무게·HS 코드를 한 트랜잭션으로 교체하고
`200 Response<ProductDetailAdminResponse>`를 반환한다. 숨김 상품도 편집할 수 있으며 삭제 상품은 404다.
응답은 기존 편집용 상세 조회와 동일하여 상품·SKU·옵션 아이디가 문자열이고 판매 상태는 code/name/description 메타데이터다.

```json
{
  "version": 0,
  "skus": [
    {
      "skuId": "4611686018427387904",
      "additionalPrice": {"amount": 0, "currency": "KRW"},
      "saleStatus": "ON_SALE",
      "weightGrams": 350,
      "hsCode": "6211.42-1000"
    }
  ]
}
```

- version은 편집용 상세의 0 이상 상품 버전이며 필수다. 성공한 요청은 동일 값이어도 매번 버전을 1 올린다.
- skus는 1~100개이며 해당 상품의 기존 SKU 아이디 집합과 정확히 같아야 한다. 중복·누락·추가·타 상품 아이디는 400이다.
  아이디는 등록 API와 같이 숫자 또는 십진수 문자열을 받는다. 100개 초과 기존 상품의 편집은 이번 API 지원 범위 밖이다.
- additionalPrice는 0~100000000의 최소 화폐 단위 정수와 상품과 같은 통화가 필수다.
  서버는 현재 기본 판매가·정가와 새 전체 SKU에 `ProductPricing.violation`을 적용한다.
  판매중 SKU가 있으면 적어도 하나의 추가금은 0이어야 한다. 모두 판매 종료면 이 조건은 적용하지 않는다.
- saleStatus는 ON_SALE 또는 STOP_SELLING이다. 은퇴한 옵션 그룹이나 값을 사용하는 SKU는 ON_SALE로 보낼 수 없다.
  판매 종료 상태의 편집은 가능하다.
- weightGrams는 null 또는 양수다. hsCode는 20자 이하 입력에서 점·하이픈·ASCII 공백을 제거하고 숫자 6~10자리로 저장한다.
  전각 숫자나 다른 문자를 고쳐 받지 않는다. null·생략한 무게/HS 코드는 삭제하고 빈 HS 코드도 삭제한다.
- 옵션 그룹·값·조합 키·SKU 아이디, 원가, 재고 수량·재고 관리 여부·재고 알림 정책과 기존 선점은 이 API의 편집 대상이 아니다.
  엔티티 필드·매핑·스키마 변경이 없어 DDL은 없다.

| 오류 | HTTP | 의미 |
| --- | --- | --- |
| SKU_ADMIN_001 | 404 | 상품 없음 또는 삭제 |
| SKU_ADMIN_002 | 409 | 기대 상품 버전 불일치 |
| SKU_ADMIN_003 | 400 | 기존 SKU 전체 집합과 불일치 |
| SKU_ADMIN_004 | 400 | 통화 또는 전체 가격 규칙 위반 |
| SKU_ADMIN_005 | 409 | 상품/SKU 잠금·쿼리·트랜잭션 시간 초과·교착 충돌 |
| SKU_ADMIN_006 | 400 | 은퇴 옵션 SKU의 판매중 요청 |
| SKU_ADMIN_101~110 | 400 | 요청 필드 검증 |

필드 검증은 version 101, skus 102, skuId 103, additionalPrice 필수 104, saleStatus 105,
weightGrams 106, hsCode 107, additionalPrice.amount 108, additionalPrice.currency 109, 경로 productId 110이다.
등록 API의 HS 교차 검증 방식과 같아 잘못된 형식의 오류 field는 `skus[0].hsCodeValid`이며 길이 오류는 `skus[0].hsCode`다.
버전·SKU 아이디·금액·무게는 소수 숫자 토큰을 허용하지 않는다(정수로 잘라 받지 않는다).
깨진 JSON·소수 숫자·정수 범위 초과·알 수 없는 enum 등 바인딩 오류는 공용 `PRODUCT_100` 봉투로 반환한다.

## 버전·잠금·원자성

Controller → SkuAdminUseCase → SkuAdminFacade → SkuBulkUpdateProcessor → Port/Adapter를 따른다.
Facade는 외부 I/O 없는 5초 트랜잭션이고 Processor는 MANDATORY다.
먼저 삭제 여부·기대 버전을 확인한 뒤 전용 JPQL CAS가 상품 id/version/deletedAt 조건으로 version과 updatedAt을 바꾼다.
이 상품 행 쓰기는 기존 가격·헤더·카테고리·이미지 편집의 상품 버전 쓰기와 충돌하여 오래된 편집의 덮어쓰기를 막는다.
CAS가 persistence context를 비운 뒤 최신 상품·전체 SKU·전체 옵션을 일괄 조회하여 집합·가격·은퇴 규칙을 검증한다.
검증 실패나 응답 상세 조회·Presenter 실패도 CAS와 SKU 변경을 함께 롤백한다.

기존 managed SkuEntity에 도메인 `withEditableProperties` 결과를 반영한다.
새 엔티티를 builder로 만들어 saveAll하지 않아 createdAt 기반 Persistable의 INSERT 오판을 피하고 원래 생성 시각·옵션 키를 보존한다.
실제 변경한 SKU만 아이디 오름차순으로 하나씩 flush하여 UPDATE 순서를 명시한다(최대 100행).
루프 안에 개별 SELECT가 없고 판매 상태 JPQL bulk UPDATE도 없다.
원가 추가·재고 알림 정책은 같은 SKU 행 잠금을 사용하므로 경합 시 제한 시간 내 성공하거나 SKU_ADMIN_005로 롤백한다.
stock 행은 쓰거나 잠그지 않으며 재고 행 없음·무제한·기존 RESERVED 선점과 항목을 그대로 보존한다.
판매 종료 후에도 이미 생성된 선점의 확정·해제는 기존 정책대로 동작하고 해제 재시도는 한 번만 수량을 복구한다.

상품 버전 CAS는 상품 편집 간 경합을 제어한다. 이미 시작한 재고 선점과 SKU 편집을 완전히 직렬화하는 장치는 아니다.
기존 선점 요청이 편집 전 판매 상태를 읽었다면 그 요청이 뒤에 완료될 수 있다. 기존 선점의 상태·수량을 소급 수정하지 않는다.

감사는 `PRODUCT_SKUS_REPLACE`의 action·actorMemberId·target=product:<id>·result 네 필드만 공통 AdminAuditLog로 기록한다.
성공/실패 판정은 상세 조립 후 예약하고 실제 커밋·롤백 결과로 확정한다. 변경 값·본문·예외 메시지·개인정보는 싣지 않는다.

## FE 연동

편집용 상세의 기존 SKU 아이디와 상품 version으로 폼을 채우고 기존 SKU 전체를 전송한다.
옵션이나 재고 조정 입력을 이 본문에 섞지 않는다. 무게·HS 코드 입력을 비우면 삭제되는 전체 교체 의미를 화면에 반영한다.
저장 성공 후 반환된 상세와 version으로 폼을 갱신한다. 409면 최신 상세를 다시 조회하고 사용자의 변경을 확인한 뒤 새 버전으로 저장한다.
응답이 유실되었을 때 같은 version으로 재전송하면 이미 반영된 요청도 409일 수 있으므로 상세를 다시 조회하여 실제 반영 내용을 확인한다.
이 API에는 별도의 Idempotency-Key가 없다. API 계약 변경에 따라 FE 리뷰가 필요하다.

## 검증 계약

`SkuAdminControllerTest`는 ADMIN JWT·요청 필드·문자열 아이디·메타데이터·자체 오류 봉투를 검증한다.
`SkuAdminAuditDeclarationTest`는 실제 컨트롤러 선언에 security-core의 감사 가드를 실행하고 선언 누락 검출도 확인한다.
`SkuBulkUpdateIntegrationTest`와 Docker/CI 조건의 `SkuBulkUpdatePostgresIntegrationTest`는 같은 계약을 상속한다.
실제 CAS/managed UPDATE·생성 시각과 옵션 보존·잘못된 집합/가격/은퇴 규칙 롤백·동일 버전 동시 편집·기존 가격 편집 경합을 검증한다.
원가/재고 알림 정책이 사용하는 SKU 행 잠금 시간 초과·stock 행 잠금과 독립인 편집·기존 선점 유지 후 해제 멱등성·네 필드 감사도 검증한다.
