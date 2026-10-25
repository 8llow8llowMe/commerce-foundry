import type { DomesticPolicyHistoryItem, DomesticPolicySnapshot } from './types';

/** 테스트용 국내 정책 이력 — 값은 모두 가짜다 */
export const krw = (amount: number) => ({ amount, currency: 'KRW' as const });

export const snapshot = (patch: Partial<DomesticPolicySnapshot> = {}): DomesticPolicySnapshot => ({
  baseFee: krw(3000),
  freeShippingThreshold: krw(50000),
  remoteAreas: [
    { name: '구간 가', postalCodeFrom: '10000', postalCodeTo: '10999', surcharge: krw(3000) },
  ],
  version: 1,
  ...patch,
});

export const historyItem = (
  patch: Partial<DomesticPolicyHistoryItem> = {},
): DomesticPolicyHistoryItem => ({
  historyId: '900',
  adminMemberId: '4611686018427387904',
  changedAt: '2026-10-18T03:00:00Z',
  before: snapshot(),
  after: snapshot({ version: 2 }),
  ...patch,
});
