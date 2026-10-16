---
name: backend-api-check
description: commerce-foundry 백엔드 REST API(경로 체계, 고객/어드민/internal 분리, Swagger, Response 봉투, 식별자 String, 금액 표현, 페이지네이션, Presenter 흐름)가 backend/docs 규칙을 따르는지 점검한다. Controller/DTO/Presenter 변경이나 PR 직전 API 계약 확인이 트리거다.
---

# Backend API Check

백엔드 API 구현이 `backend/docs` 규칙과 일치하는지 점검한다. 코드를 고치지 않고 보고한다.

## When to Use

- Controller, request/response DTO, Presenter 를 추가·수정했을 때
- 어드민 API 를 추가했을 때
- PR 직전 API 계약(FE 가 보는 모양)을 확인할 때

## Read First

1. [backend/docs/api-design-guide.md](../../../backend/docs/api-design-guide.md)
2. [backend/docs/coding-conventions.md](../../../backend/docs/coding-conventions.md) §5~8, §11
3. [backend/docs/done-checklist.md](../../../backend/docs/done-checklist.md)

## 대상 확보

```bash
git diff --name-only origin/develop...HEAD -- backend
git status --short -- backend
```

새로 생긴 Controller 는 전부 본다.

## What to Check

1. **경로 체계**
   - 고객 `/api/v1/**` ↔ `adapter/in/web`, 어드민 `/api/v1/admin/**` ↔ `adapter/in/admin`, 서비스 간 `/internal/v1/**` ↔ `adapter/in/internal`
   - 경로 접두어가 `api-design-guide.md` §1-2 의 **서비스 소유 표**와 맞는가 (다른 서비스 접두어를 쓰면 게이트웨이가 엉뚱한 곳으로 보낸다)
   - 복수형 kebab-case, 상위 → 하위 리소스 순, 동사 경로 없음

2. **Controller 흐름**
   - Controller 는 `*WebUseCase` / `*AdminUseCase` / `*InternalUseCase` 만 호출한다
   - 반환은 `ResponseEntity<Response<T>>`

3. **고객 / 어드민 분리**
   - 어드민 DTO 를 고객 Controller 가 쓰지 않는다 (반대도)
   - 고객 응답에 원가·재고 수량·내부 메모·다른 회원 정보 같은 **어드민 전용 필드가 없다**
   - 어드민 Controller 에 `@PreAuthorize("hasRole('ADMIN')")` 가 있다

4. **응답 계약**
   - 응답 DTO 의 모든 `*Id` 가 `String` (§7-1), `@Schema example` 도 문자열
   - 금액은 `{amount, currency}` 객체이고 `amount` 가 최소 화폐 단위 정수
   - enum 은 `{code, name, description}` metadata 객체
   - 목록: 고객 `SliceResponse`, 어드민 `PageResponse`. `totalCount` 를 Presenter 가 `items.size()` 로 채우지 않는다
   - 내부 `Info` 가 외부로 바로 노출되지 않는다

5. **오류 계약**
   - 도메인 `ExceptionHandler` 가 있고 `ValidationErrorSupport` 에 위임한다 (없으면 봉투 없는 400 이 나간다)
   - 검증 메시지가 `*ValidationMessage` 상수를 참조하고, 코드가 필드별 `1xx` 대역이다
   - 타인 리소스 접근이 404 다

6. **보안 · 멱등**
   - 인증 API 에 `@PreAuthorize`, member 식별은 JWT claim (요청 바디의 memberId 를 믿지 않는다)
   - internal Controller 의 모든 메서드에 허용 호출자 `@PreAuthorize("hasAuthority('SERVICE_<호출 서비스>')")` 가 있다 (`security-guide.md` §9-4)
   - 주문 생성·결제·취소 같은 쓰기 API 가 멱등 키를 받는다

7. **Swagger**
   - `@Tag` (어드민은 `[어드민] ...`), `@Operation`, `@Parameter`, `@Schema` 한국어 설명
   - 인증 API 는 `@SecurityRequirement`, internal 은 `@Hidden`

## Output Format

```text
BACKEND API CHECK
=================

Scope: [controller or feature]

Findings:
1. [CRITICAL|HIGH|MEDIUM|LOW] [file:line]
   - issue
   - expected rule (doc §)
   - recommended fix

FE Contract Impact:
- [경로·필드·오류 코드 변경 — seonghoho 에게 알릴 것] (없으면 "없음")

Confirmed:
- [이미 규칙에 맞는 항목]
```
