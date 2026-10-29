import { ApiError, type Money } from '@cf/api-client';
import type { Locale } from '@cf/ui';
import { toAddressBody, type AddressDraft } from '@/lib/account/address-form';
import type { ShippingAddress } from '@/lib/account/types';
import type { CartLine } from '@/lib/cart/types';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { quoteRequest, type QuoteLine, type QuoteRequest, type ShippingQuote } from './quote';

/**
 * 주문 생성 `POST /api/v1/orders`(order-service, BE #13 C3). 로그인 회원만, UUID `Idempotency-Key` 가 필수다.
 * 서버가 미리보기와 같은 계산으로 금액을 다시 정하고 재고를 선점해 결제 대기(`PENDING_PAYMENT`) 주문을 만든다 — 새 주문 201, 같은 키 · 같은 본문 재요청 200.
 * `expectedTotalAmount` 는 화면이 보여 준 총액이다. 지금 계산과 다르면 재고를 잡기 전에 409 `ORDER_010` 이다.
 * 정본은 `backend/docs/api-design-guide.md` 주문 생성 절 · `backend/docs/order-flow-guide.md` §2.
 */
export const ORDERS_PATH = '/api/v1/orders';

/** 주문 시점 배송지 스냅숏. 형식(연락처 · 우편번호 · 해외 도시)은 서버가 본다 */
export type OrderShippingAddress = {
  recipientName: string;
  recipientPhone: string;
  countryCode: string;
  postalCode: string | null;
  addressLine1: string;
  addressLine2: string | null;
  city: string | null;
  stateProvince: string | null;
};

export type OrderCreateRequest = {
  items: QuoteRequest['items'];
  shippingAddress: OrderShippingAddress;
  expectedTotalAmount: Money;
};

export type OrderCreated = {
  orderId: string;
  status: { code: string; name: string; description: string };
  /** 결제 기한(UTC ISO-8601) — 재고 선점 만료 시각이자 미결제 만료 기준 */
  paymentDueAt: string;
  currency: string;
  items: QuoteLine[];
  amounts: { itemsAmount: Money; shippingFee: Money; totalAmount: Money };
  shipping: ShippingQuote;
  shippingAddress: OrderShippingAddress & { deliveryMemo: string | null };
  createdAt: string;
};

/** 저장된 배송지 → 주문 배송지(라벨 · 기본 여부는 싣지 않는다) */
export function savedShippingAddress(address: ShippingAddress): OrderShippingAddress {
  return {
    recipientName: address.recipientName,
    recipientPhone: address.recipientPhone,
    countryCode: address.countryCode,
    postalCode: address.postalCode,
    addressLine1: address.addressLine1,
    addressLine2: address.addressLine2,
    city: address.city,
    stateProvince: address.stateProvince,
  };
}

/** 이 주문에만 쓰는 새 배송지 → 주문 배송지. 공백 정리 · 빈 칸 null · 국내 도시/주 비우기는 배송지 저장과 같다 */
export function draftShippingAddress(draft: AddressDraft): OrderShippingAddress {
  const body = toAddressBody(draft, false);
  return savedShippingAddress({ ...body, addressId: '', isDefault: false });
}

/** 주문 본문 — 줄은 견적과 같은 모양이고, 기대 총액은 화면에 보인 견적 총액이다 */
export function orderRequest(
  lines: CartLine[],
  shippingAddress: OrderShippingAddress,
  expectedTotalAmount: Money,
): OrderCreateRequest {
  return {
    items: quoteRequest(lines, {
      countryCode: shippingAddress.countryCode,
      postalCode: shippingAddress.postalCode,
    }).items,
    shippingAddress,
    expectedTotalAmount,
  };
}

/**
 * 주문 생성 실패를 화면 처리로 가른다. `sameKey` 는 다음 누름에 같은 `Idempotency-Key` 를 쓸지다
 * — 5xx · 연결 실패 · 502/504 · 409 `ORDER_018`(처리 중) · 429 는 요청이 반영됐을 수 있거나 서비스에 닿지 않았으므로 같은 키로 다시 보내고,
 * 그 밖의 4xx 는 이 시도가 끝났으므로 새 키를 쓴다(BE `OrderWebController` 계약).
 * - signedOut: 401 — 세션이 끝났다
 * - amountChanged: 409 `ORDER_010` — 금액이 바뀌었다. 견적을 다시 받아 새 금액을 확인하게 한다
 * - stock: 409 `ORDER_016` — 재고 부족. 장바구니에서 수량을 고치게 한다
 * - cart: 담은 상품을 고쳐야 한다(판매 불가 · 수량 상한 · 통화 혼합 · 맞춤 입력 등) — 장바구니로 안내
 * - destination: 배송지를 고쳐야 한다(배송 불가 · 받는 사람 · 연락처 · 주소 형식)
 * - retry: 잠시 뒤 다시 누른다
 */
export type OrderFailure =
  | { kind: 'signedOut'; sameKey: false }
  | { kind: 'amountChanged'; message: string; sameKey: false }
  | { kind: 'stock'; message: string; sameKey: false }
  | { kind: 'cart'; message: string; sameKey: false }
  | { kind: 'destination'; message: string; sameKey: false }
  | { kind: 'retry'; message: string; sameKey: boolean };

const CART_CODES = new Set([
  'ORDER_003',
  'ORDER_004',
  'ORDER_005',
  'ORDER_006',
  'ORDER_007',
  'ORDER_008',
  'ORDER_015',
]);
/** 배송 불가 · 배송지 칸 형식(116~128 · 138~140) */
const DESTINATION_CODES = new Set([
  'ORDER_012',
  'ORDER_013',
  'ORDER_014',
  ...[116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127, 128, 138, 139, 140].map(
    (n) => `ORDER_${n}`,
  ),
]);
/** 같은 키로 다시 보낼 수 있는 결과 — 반영 여부를 모르거나 서비스에 닿지 않았다. `server` 는 5xx 일 때만이다(그 밖의 400 도 server 다) */
const SAME_KEY_KINDS = new Set<ApiError['kind']>([
  'network',
  'invalidResponse',
  'unknownOutcome',
  'unavailable',
  'rateLimited',
]);
const sameKeyOutcome = (cause: ApiError) =>
  SAME_KEY_KINDS.has(cause.kind) || (cause.kind === 'server' && (cause.status ?? 500) >= 500);

export function orderFailure(
  cause: unknown,
  t: Dictionary['checkout'],
  locale: Locale,
): OrderFailure {
  // ApiError 가 아니면(예상 못 한 예외) 반영 여부를 모른다 — 같은 키로 다시
  if (!(cause instanceof ApiError)) return { kind: 'retry', message: t.orderFailed, sameKey: true };
  if (cause.kind === 'unauthorized') return { kind: 'signedOut', sameKey: false };
  const code = cause.code ?? '';
  const textOf = (fallback: string) =>
    t.orderErrors[code] ?? t.errors[code] ?? (locale === 'ko' ? cause.message : fallback);

  if (cause.kind === 'rateLimited') {
    return { kind: 'retry', message: t.orderErrors.GATEWAY_006 ?? t.orderFailed, sameKey: true };
  }
  if (code === 'ORDER_018') return { kind: 'retry', message: textOf(t.orderFailed), sameKey: true };
  if (sameKeyOutcome(cause)) {
    return {
      kind: 'retry',
      message: t.orderErrors[code] ?? t.errors[code] ?? t.orderFailed,
      sameKey: true,
    };
  }
  if (code === 'ORDER_010') {
    return { kind: 'amountChanged', message: textOf(t.orderFailed), sameKey: false };
  }
  if (code === 'ORDER_016') {
    return { kind: 'stock', message: textOf(t.cartInvalid), sameKey: false };
  }
  const destinationField = cause.fieldErrors.find((item) =>
    item.field.startsWith('shippingAddress'),
  );
  if (destinationField || DESTINATION_CODES.has(code)) {
    // 칸 검증 서버 문구에는 필드 이름(영문 식별자)이 섞여 있어 사전 문구 · 범용 문구만 쓴다
    const shown = destinationField?.code || code;
    const message = t.orderErrors[shown] ?? t.errors[shown] ?? t.destinationInvalid;
    return { kind: 'destination', message, sameKey: false };
  }
  if (CART_CODES.has(code)) return { kind: 'cart', message: textOf(t.cartInvalid), sameKey: false };
  if (cause.kind === 'validation') return { kind: 'cart', message: t.cartInvalid, sameKey: false };
  // 그 밖의 4xx(019 키 재사용 · 198/199 헤더 · 403 등) — 이 시도는 끝났다. 새 키로 다시 누를 수 있다
  return { kind: 'retry', message: t.orderErrors[code] ?? t.orderFailed, sameKey: false };
}

/** 주문하기 한 번의 의도 — 직렬화한 본문과 그 본문에 쓴 멱등 키 */
export type OrderAttempt = { body: string; idempotencyKey: string };

/**
 * 이번 누름에 쓸 시도. 앞 시도가 같은 키로 다시 보내도 되는 실패로 끝났고(호출자가 남겨 둔 시도) 본문이 같으면 그 키를 이어 쓰고,
 * 아니면 새 키를 만든다 — 같은 키에 다른 본문을 보내면 409 `ORDER_019` 이므로 본문이 바뀌면(배송지 변경 · 새 견적) 언제나 새 키다.
 */
export function nextAttempt(
  previous: OrderAttempt | null,
  body: string,
  createKey: () => string,
): OrderAttempt {
  return previous && previous.body === body ? previous : { body, idempotencyKey: createKey() };
}

/** 결제 기한 표시 — 브라우저 시간대의 날짜 · 시각(분까지) */
export function formatDueAt(iso: string, locale: Locale, timeZone?: string): string {
  return new Intl.DateTimeFormat(locale === 'ko' ? 'ko-KR' : 'en-US', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone,
  }).format(new Date(iso));
}
