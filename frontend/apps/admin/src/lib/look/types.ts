// product-service 어드민 룩(Shop the Look) API 계약(BE #62 · #370, `backend/docs/api-design-guide.md` §2-11).
// 정본은 BE DTO(@Schema) — LookAdminResponse · LookProductAdminItem · LookUpsertRequest · LookOrderRequest 다.
// 식별자는 JS 안전 정수를 넘으므로 항상 string 이다. 제목 · 설명 · 대체 텍스트는 평문이다(텍스트 노드로만 그린다).

/** 목록 · 생성은 이 경로, 단건은 `/{lookId}`, 순서 변경은 `/order` */
export const LOOKS_PATH = '/api/v1/admin/looks';
export const LOOK_ORDER_PATH = `${LOOKS_PATH}/order`;

export const lookPath = (lookId: string) => `${LOOKS_PATH}/${encodeURIComponent(lookId)}`;

/** BE `CodeNameDescriptionMetadata` — 화면은 name 을 보인다 */
export type CodeName = { code: string; name: string; description: string };

export type LookImageAdmin = {
  url: string;
  /** 원본 크기(px). 모르면 null */
  width: number | null;
  height: number | null;
  altText: string | null;
};

export type LookProductAdmin = {
  productId: string;
  /** 상품 행이 없으면(데이터 오류) null */
  name: string | null;
  /** false 면 고객 룩에서 이 상품이 빠진다 */
  visible: boolean;
  /** 저장한 판매 상태 — 판매중(ON_SALE) · 판매 종료(STOP_SELLING). 상품 행이 없으면 null */
  saleStatus: CodeName | null;
};

/** 어드민 룩 — 내부 순서 번호 · 아이템 아이디는 오지 않는다(#305). 순서는 목록의 배열 순서다 */
export type LookAdmin = {
  lookId: string;
  title: string;
  description: string | null;
  image: LookImageAdmin;
  visible: boolean;
  /** 배열 순서 = 구성 순서 */
  products: LookProductAdmin[];
};

export type LookList = { looks: LookAdmin[] };

/** 생성 · 수정(전체 교체) 본문 — `productIds` 의 배열 순서가 구성 순서다 */
export type LookRequest = {
  title: string;
  description: string | null;
  image: { url: string; width: number | null; height: number | null; altText: string | null };
  visible: boolean;
  productIds: string[];
};

export type LookOrderRequest = { lookIds: string[] };

/** BE 요청 검증 · 룩 규칙의 상한(`LookUpsertRequest` · `LookImageRequest` · `LookProcessor`) */
export const LOOK_LIMITS = {
  title: 100,
  description: 1000,
  imageUrl: 500,
  altText: 200,
  products: 20,
  looks: 100,
} as const;
