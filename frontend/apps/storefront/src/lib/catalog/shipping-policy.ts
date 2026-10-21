import { formatMoney, type Money } from '@cf/api-client';
import type { Locale } from '@cf/ui';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { fill } from '@/lib/i18n/format';

// delivery-service 배송비 정책 안내 계약(`/api/v1/shipping-fees/policy` · `/international-policy`, BE #11 · #22).
// 정책이 아직 없거나 해외 배송을 하지 않으면 dataBody 가 null 이다. 화면은 표시만 하고, 실제 배송비는 주문서 견적(#13)이 정한다.

type RemoteArea = { name: string; surcharge: Money };

export type ShippingPolicy = {
  baseFee: Money;
  /** 이 금액 이상(할인 후 상품 합계)이면 기본 배송비가 없다. null 이면 무료배송 없음 */
  freeShippingThreshold: Money | null;
  /** 국내만 — 무료배송이어도 붙는 추가 배송비 */
  remoteAreas: RemoteArea[];
  /** 해외만 — 관세 · 부가세 안내(어드민 입력 문구) */
  customsNotice: string | null;
  international: boolean;
};

export type ShippingNotice = {
  /** 상품 상세 배송 안내 줄 */
  lines: string[];
  /** 장바구니 안내 — 무료배송 기준이 있을 때만 */
  freeShipping: string | null;
};

function readMoney(value: unknown): Money | null {
  if (typeof value !== 'object' || value === null) return null;
  const { amount, currency } = value as Record<string, unknown>;
  if (typeof amount !== 'number' || !Number.isFinite(amount)) return null;
  if (currency !== 'KRW' && currency !== 'USD') return null;
  return { amount, currency };
}

/**
 * 정책 응답(dataBody)을 읽는다. null · 모양이 다르면 null — 안내를 그리지 않는다(틀린 금액을 보이지 않게).
 * 도서산간 구간 중 모양이 다른 줄만 버린다.
 */
export function readShippingPolicy(value: unknown, international: boolean): ShippingPolicy | null {
  if (typeof value !== 'object' || value === null) return null;
  const body = value as Record<string, unknown>;
  const baseFee = readMoney(body.baseFee);
  if (!baseFee) return null;
  const freeShippingThreshold =
    body.freeShippingThreshold == null ? null : readMoney(body.freeShippingThreshold);
  if (body.freeShippingThreshold != null && !freeShippingThreshold) return null;

  const remoteAreas: RemoteArea[] = [];
  if (!international && Array.isArray(body.remoteAreas)) {
    for (const area of body.remoteAreas as unknown[]) {
      const item = (area ?? {}) as Record<string, unknown>;
      const surcharge = readMoney(item.surcharge);
      if (typeof item.name === 'string' && item.name && surcharge) {
        remoteAreas.push({ name: item.name, surcharge });
      }
    }
  }
  const customsNotice =
    international && typeof body.customsNotice === 'string' && body.customsNotice.trim()
      ? body.customsNotice.trim()
      : null;
  return { baseFee, freeShippingThreshold, remoteAreas, customsNotice, international };
}

/**
 * 정책을 안내 문구로. 금액은 서버 값을 그대로 표시한다 — 장바구니 합계와 비교하거나 남은 금액을 계산하지 않는다.
 * 해외 배송비는 기본료 + 무게 요금이라 기본료는 "부터" 로 안내한다.
 */
export function shippingNotice(
  policy: ShippingPolicy,
  locale: Locale,
  t: Dictionary['shipping'],
): ShippingNotice {
  const money = (value: Money) => formatMoney(value, locale);
  const lines: string[] = [];

  if (policy.international) {
    lines.push(fill(t.internationalBase, { fee: money(policy.baseFee) }));
  } else {
    lines.push(
      policy.baseFee.amount === 0 ? t.free : fill(t.baseFee, { fee: money(policy.baseFee) }),
    );
  }

  const freeShipping =
    policy.freeShippingThreshold && policy.baseFee.amount > 0
      ? fill(t.freeOver, { amount: money(policy.freeShippingThreshold) })
      : null;
  if (freeShipping) lines.push(freeShipping);

  if (policy.remoteAreas.length > 0) {
    const areas = policy.remoteAreas
      .map((area) => fill(t.remoteArea, { name: area.name, fee: money(area.surcharge) }))
      .join(', ');
    lines.push(fill(t.remote, { areas }));
  }
  if (policy.customsNotice) lines.push(policy.customsNotice);

  return { lines, freeShipping };
}
