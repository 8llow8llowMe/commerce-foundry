import { apiFetch } from '@cf/api-client';
import type { Locale } from '@cf/ui';

/**
 * 고객 배송 조회(delivery-service `ShipmentWebController`, BE #18 PR-B2). 정본은 `backend/docs/api-design-guide.md` §2-19 ·
 * `backend/docs/order-flow-guide.md` 고객 노출. 송장이 없거나(출고 전) 남의 주문 · 출고 반영 전이면 404 가 아니라 200 + null 이다 —
 * 주문이 있는지는 주문 상세가 정한다. 배송 완료가 주문에 반영되기 전이면 서버가 배송 중으로 내린다(주문 상세와 맞춘다).
 */
export const DELIVERIES_PATH = '/api/v1/deliveries';

export type DeliveryMetadata = { code: string; name: string; description: string };

export type Delivery = {
  deliveryId: string;
  orderId: string;
  /** 서버 `CarrierCode` — 목록에 없는 택배사는 `OTHER` */
  carrier: DeliveryMetadata;
  trackingNumber: string;
  /** `SHIPPED` · `IN_TRANSIT` · `DELIVERED` */
  status: DeliveryMetadata;
  shippedAt: string;
  /** 배송 완료가 아니거나 주문 반영 전이면 null */
  deliveredAt: string | null;
};

export const orderDeliveryPath = (orderId: string) =>
  `${DELIVERIES_PATH}?orderId=${encodeURIComponent(orderId)}`;

/**
 * 화면의 배송 자리 상태. null 은 송장이 없다(출고 전 · 반영 전)는 뜻이라 자리를 두지 않는다.
 * `retrying` 은 실패 뒤 다시 읽는 중 — 버튼을 남긴 채 막아 포커스를 잃지 않고 연속 클릭도 막는다.
 */
export type DeliveryLoad =
  | { status: 'loading' }
  | { status: 'failed'; retrying: boolean }
  | { status: 'ready'; delivery: Delivery | null };

/**
 * 배송을 읽어 화면 상태로 바꾼다. 실패는 kind 와 상관없이 모두 `failed` 다 — 401 · 404 는 같이 부른 주문 상세가 받아 화면을 바꾸고,
 * 읽기라 결과 모름 · 429 를 따로 다룰 것이 없다.
 */
export function readOrderDelivery(
  orderId: string,
  fetchImpl?: typeof fetch,
): Promise<DeliveryLoad> {
  return apiFetch<Delivery | null>(orderDeliveryPath(orderId), {}, fetchImpl).then(
    (delivery): DeliveryLoad => ({ status: 'ready', delivery }),
    (): DeliveryLoad => ({ status: 'failed', retrying: false }),
  );
}

/**
 * 택배사 배송 조회 페이지 — 서버 추적(`ShipmentTrackingPort`)은 후속이라 택배사 공개 조회 주소에 송장 번호를 붙여 새 창으로 연다.
 * 브랜드와 무관한 택배사 사실이라 공통 코드에 둔다. `OTHER` · 모르는 택배사는 링크를 두지 않는다(송장 번호만 보인다).
 * 주소는 택배사가 바꿀 수 있다 — 2026-11-02 에 각 주소가 열리는 것을 확인했다(DHL 은 봇 차단으로 응답 확인 못 함).
 */
const TRACKING_URLS: Record<string, (trackingNumber: string) => string> = {
  CJ_LOGISTICS: (no) => `https://trace.cjlogistics.com/next/tracking.html?wblNo=${no}`,
  KOREA_POST: (no) => `https://service.epost.go.kr/trace.RetrieveDomRigiTraceList.comm?sid1=${no}`,
  LOTTE: (no) => `https://www.lotteglogis.com/home/reservation/tracking/linkView?InvNo=${no}`,
  HANJIN: (no) =>
    `https://www.hanjin.com/kor/CMS/DeliveryMgr/WaybillResult.do?mCode=MN038&schLang=KR&wblnumText2=${no}`,
  LOGEN: (no) => `https://www.ilogen.com/web/personal/trace/${no}`,
  EMS: (no) => `https://service.epost.go.kr/trace.RetrieveEmsRigiTraceList.comm?POST_CODE=${no}`,
  DHL: (no) =>
    `https://www.dhl.com/global-en/home/tracking/tracking-express.html?submit=1&tracking-id=${no}`,
  FEDEX: (no) => `https://www.fedex.com/fedextrack/?trknbr=${no}`,
  UPS: (no) => `https://www.ups.com/track?tracknum=${no}`,
};

/** 서버 `Shipment.TRACKING_NUMBER_PATTERN` — 어긋난 값은 주소에 싣지 않는다 */
const TRACKING_NUMBER_PATTERN = /^[A-Za-z0-9-]{1,50}$/;

export function trackingUrl(delivery: Pick<Delivery, 'carrier' | 'trackingNumber'>): string | null {
  const build = TRACKING_URLS[delivery.carrier.code];
  if (!build || !TRACKING_NUMBER_PATTERN.test(delivery.trackingNumber)) return null;
  return build(encodeURIComponent(delivery.trackingNumber));
}

/**
 * 택배사 · 배송 상태 이름 — 서버 이름은 국문 고정이라 영문 스토어는 사전에서 코드로 고른다(없는 코드는 서버 이름).
 * 국문은 서버 이름을 그대로 쓴다(`statusLabel` 과 같은 방식).
 */
export function deliveryLabel(
  metadata: DeliveryMetadata,
  names: Record<string, string>,
  locale: Locale,
): string {
  return locale === 'ko' ? metadata.name : (names[metadata.code] ?? metadata.name);
}
