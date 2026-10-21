import { cache } from 'react';
import { getI18n } from '@/lib/i18n/server';
import { serverFetch } from '@/lib/server-api';
import { readShippingPolicy, shippingNotice, type ShippingNotice } from './shipping-policy';

/**
 * 지금 언어의 배송비 안내. 국문은 국내 정책, 영문은 해외 정책이다(영문 스토어 고객은 해외 배송).
 * 정책이 없거나(null) 조회가 실패하면 null — 안내 없이 화면은 그대로 뜬다. 한 요청 안에서는 한 번만 부른다.
 */
export const loadShippingNotice = cache(async (): Promise<ShippingNotice | null> => {
  const { locale, dict } = await getI18n();
  const international = locale !== 'ko';
  const path = international
    ? '/api/v1/shipping-fees/international-policy'
    : '/api/v1/shipping-fees/policy';
  const body = await serverFetch<unknown>(path).catch(() => null);
  const policy = readShippingPolicy(body, international);
  return policy ? shippingNotice(policy, locale, dict.shipping) : null;
});
