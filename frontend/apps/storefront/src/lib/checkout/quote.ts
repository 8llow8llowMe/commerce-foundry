import { ApiError, type Money } from '@cf/api-client';
import type { CartLine, CartResponse, CustomOption } from '@/lib/cart/types';
import type { Locale } from '@cf/ui';
import type { Dictionary } from '@/lib/i18n/dictionaries';

/**
 * 주문서 금액 미리보기 `POST /api/v1/orders/quotes`(order-service, BE #13 B2). 로그인 회원만, 저장 없이 계산만 한다.
 * 금액은 서버가 SKU 판매가와 배송비 견적으로 정한다 — 화면은 받은 값을 표시만 한다(`backend/docs/order-flow-guide.md` §2).
 * 미리보기는 재고를 보지 않는다(재고 부족은 주문 생성에서 난다). 판매 불가 줄이 하나라도 있으면 줄 표시가 아니라 전체 실패(409 ORDER_003)다.
 */
export const QUOTES_PATH = '/api/v1/orders/quotes';

/** 견적 배송지 — 국내(KR)는 우편번호 5자리가 필수, 해외는 선택이다(형식은 서버가 본다) */
export type QuoteDestination = { countryCode: string; postalCode: string | null };

export type QuoteRequest = {
  items: { skuId: string; quantity: number; customOptions?: CustomOption[] }[];
  destination: QuoteDestination;
};

export type QuoteLine = {
  skuId: string;
  productId: string;
  productName: string;
  /** 옵션 값을 " / " 로 이은 이름. 옵션이 없으면 빈 문자열 */
  optionName: string;
  unitPrice: Money;
  quantity: number;
  lineAmount: Money;
  customOrder: boolean;
  customOptions: CustomOption[];
};

export type ShippingQuote = {
  scope: 'DOMESTIC' | 'INTERNATIONAL';
  baseFee: Money;
  freeShippingApplied: boolean;
  /** 도서산간 추가금 — 아니면 0. 무료배송이어도 붙는다 */
  remoteAreaSurcharge: Money;
  remoteAreaName: string | null;
  freeShippingThreshold: Money | null;
  /** 해외 무게 요금 — 국내는 null */
  weightFee: Money | null;
  /** 해외 국가 그룹 이름 — 국내는 null */
  shippingZoneName: string | null;
};

export type OrderQuote = {
  currency: string;
  /** 요청 순서 */
  items: QuoteLine[];
  amounts: { itemsAmount: Money; shippingFee: Money; totalAmount: Money };
  shipping: ShippingQuote;
};

/**
 * 장바구니에서 주문서로 보낼 줄 — 구매 가능하고(ON_SALE) 수량이 상한 안인 줄만. 장바구니 합계와 같은 기준이다.
 * 판매 불가 줄을 보내면 견적 전체가 409 로 거절되므로 미리 빼고, 뺀 줄은 화면에 따로 알린다.
 */
export function orderLines(cart: CartResponse): { lines: CartLine[]; excluded: CartLine[] } {
  const lines: CartLine[] = [];
  const excluded: CartLine[] = [];
  for (const line of cart.items) {
    (line.status.code === 'ON_SALE' && !line.quantityExceeded ? lines : excluded).push(line);
  }
  return { lines, excluded };
}

/** 저장된 · 새로 입력한 배송지 → 견적 배송지. 우편번호는 앞뒤 공백을 빼고 비면 null 이다 */
export function quoteDestination(address: {
  countryCode: string;
  postalCode: string | null;
}): QuoteDestination {
  const postalCode = address.postalCode?.trim() ?? '';
  return { countryCode: address.countryCode, postalCode: postalCode === '' ? null : postalCode };
}

/** 견적 본문. skuId 는 JS 안전 정수를 넘을 수 있어 문자열 그대로 보낸다. 맞춤 입력이 없으면 싣지 않는다 */
export function quoteRequest(lines: CartLine[], destination: QuoteDestination): QuoteRequest {
  return {
    items: lines.map((line) =>
      line.customOptions.length > 0
        ? { skuId: line.skuId, quantity: line.quantity, customOptions: line.customOptions }
        : { skuId: line.skuId, quantity: line.quantity },
    ),
    destination,
  };
}

/**
 * 견적 실패를 화면 처리로 가른다.
 * - signedOut: 401 — 세션이 끝났다
 * - cart: 담은 상품을 고쳐야 한다(판매 불가 · 수량 상한 · 통화 혼합 · 해외 배송 정보 없음 · 중복 · 맞춤 입력 · 무게 초과) — 장바구니로 안내
 * - destination: 배송지를 바꿔야 한다(배송 불가 국가 · 통화 · 정책 없음 · 국가 · 우편번호 형식)
 * - retry: 잠시 뒤 같은 요청이 될 수 있다(503 원격 실패 · 429 · 연결 끊김) — 다시 계산 버튼
 * 문구는 사전의 코드별 문구다. 사전에 없는 코드는 국문 스토어에서 서버 문구(국문), 영문 스토어에서 묶음별 범용 문구다
 * — 서버 국문을 영문 화면에 보이지 않는다. 국문 사전은 상품 이름 목록이 붙는 코드(003 · 004 · 005 · 007 · 008)를 비워 서버 문구(이름 포함)를 쓴다.
 */
export type QuoteFailure =
  | { kind: 'signedOut' }
  | { kind: 'cart'; message: string }
  | { kind: 'destination'; message: string }
  | { kind: 'retry'; message: string };

const CART_CODES = new Set([
  'ORDER_003',
  'ORDER_004',
  'ORDER_005',
  'ORDER_006',
  'ORDER_007',
  'ORDER_008',
  // 무게 초과 — 고칠 곳은 수량(장바구니)이다
  'ORDER_015',
]);
const DESTINATION_CODES = new Set([
  'ORDER_012',
  'ORDER_013',
  'ORDER_014',
  'ORDER_116',
  'ORDER_121',
  'ORDER_122',
  'ORDER_123',
  'ORDER_124',
]);

export function quoteFailure(
  cause: unknown,
  t: Dictionary['checkout'],
  locale: Locale,
): QuoteFailure {
  if (!(cause instanceof ApiError)) return { kind: 'retry', message: t.quoteFailed };
  if (cause.kind === 'unauthorized') return { kind: 'signedOut' };
  if (cause.kind === 'rateLimited') {
    return { kind: 'retry', message: t.errors.GATEWAY_006 ?? t.quoteFailed };
  }
  const serverText = locale === 'ko';
  const textOf = (code: string, fallback: string) =>
    t.errors[code] ?? (serverText ? cause.message : fallback);

  // 배송지 칸 검증(우편번호 · 국가)은 fieldErrors 로 온다
  const destinationField = cause.fieldErrors.find((item) => item.field.startsWith('destination'));
  if (destinationField) {
    return {
      kind: 'destination',
      message: textOf(destinationField.code || cause.code || '', t.destinationInvalid),
    };
  }
  if (cause.code && DESTINATION_CODES.has(cause.code)) {
    return { kind: 'destination', message: textOf(cause.code, t.destinationInvalid) };
  }
  if (cause.code && CART_CODES.has(cause.code)) {
    return { kind: 'cart', message: textOf(cause.code, t.cartInvalid) };
  }
  // 그 밖의 검증(줄 · 맞춤 입력 형식)은 장바구니 데이터 문제다 — 고칠 곳은 장바구니다
  if (cause.kind === 'validation') return { kind: 'cart', message: t.cartInvalid };
  return { kind: 'retry', message: (cause.code && t.errors[cause.code]) || t.quoteFailed };
}
