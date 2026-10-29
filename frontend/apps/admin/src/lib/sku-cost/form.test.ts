import { describe, expect, it } from 'vitest';
import {
  AMOUNT_FORMAT,
  AMOUNT_REQUIRED,
  costForm,
  costRequest,
  currentCostRows,
  lockedCurrency,
  REASON_CHARACTER,
  REASON_REQUIRED,
  REASON_TOO_LONG,
  START_FORMAT,
  START_IN_FUTURE,
  START_TOO_EARLY,
  withCostType,
  type CostForm,
} from './form';
import { costItem, currentOf, packagingItem } from './test-fixtures';
import { skuCostHistoriesPath, skuCostsPath } from './types';

const EMPTY = currentOf();
const NOW = Date.parse('2026-10-29T03:00:00Z');

const formOf = (patch: Partial<CostForm> = {}): CostForm => ({
  ...costForm(EMPTY),
  amount: '12,000',
  reason: '10월 원단 단가 인상',
  ...patch,
});

describe('경로', () => {
  it('skuId 를 인코딩하고 이력은 유형이 있을 때만 costType 을 싣는다', () => {
    expect(skuCostsPath('901')).toBe('/api/v1/admin/skus/901/costs');
    expect(skuCostHistoriesPath('901', { costType: '', page: 0 })).toBe(
      '/api/v1/admin/skus/901/costs/histories?page=0&size=20',
    );
    expect(skuCostHistoriesPath('9/1', { costType: 'PACKAGING', page: 2 })).toBe(
      '/api/v1/admin/skus/9%2F1/costs/histories?page=2&size=20&costType=PACKAGING',
    );
  });
});

describe('currentCostRows', () => {
  it('유형 순서대로 두고 응답에 없는 유형은 null(미입력)이다 — 0원과 다르다', () => {
    const rows = currentCostRows(currentOf(packagingItem()));
    expect(rows.map((row) => row.costType)).toEqual(['GOODS', 'PACKAGING']);
    expect(rows[0]?.item).toBeNull();
    expect(rows[1]?.item?.amount).toEqual({ amount: 0, currency: 'KRW' });
  });
});

describe('lockedCurrency · withCostType', () => {
  const current = currentOf(costItem({ amount: { amount: 900, currency: 'USD' } }));

  it('원가가 있는 유형은 그 통화로 고정, 미입력 유형은 고정이 없다', () => {
    expect(lockedCurrency(current, 'GOODS')).toBe('USD');
    expect(lockedCurrency(current, 'PACKAGING')).toBeNull();
    expect(costForm(current).currency).toBe('USD');
  });

  it('원가가 있는 유형으로 바꾸면 통화를 맞추고 시작을 지금부터로 되돌린다', () => {
    const form = { ...costForm(current, 'PACKAGING'), start: 'at' as const };
    expect(withCostType(form, current, 'GOODS')).toMatchObject({ currency: 'USD', start: 'now' });
    expect(withCostType(form, current, 'PACKAGING')).toMatchObject({ start: 'at' });
  });
});

describe('costRequest', () => {
  it('지금부터는 effectiveFrom 을 빼고 금액은 최소 단위 정수 · 사유는 앞뒤 공백을 뺀다', () => {
    expect(costRequest(formOf({ reason: '  인상  ' }), EMPTY, NOW)).toEqual({
      ok: true,
      request: { costType: 'GOODS', amount: { amount: 12000, currency: 'KRW' }, reason: '인상' },
    });
  });

  it('0 은 0원으로 보낸다(미입력이 아니다)', () => {
    const built = costRequest(formOf({ amount: '0' }), EMPTY, NOW);
    expect(built.ok && built.request.amount).toEqual({ amount: 0, currency: 'KRW' });
  });

  it('USD 는 센트로 바꾼다 — 계산이 아니라 표기 변환이다', () => {
    const built = costRequest(formOf({ amount: '12.5', currency: 'USD' }), EMPTY, NOW);
    expect(built.ok && built.request.amount).toEqual({ amount: 1250, currency: 'USD' });
  });

  it('빈 금액 · 형식이 틀린 금액은 보내지 않는다', () => {
    expect(costRequest(formOf({ amount: '' }), EMPTY, NOW)).toEqual({
      ok: false,
      errors: { amount: [AMOUNT_REQUIRED] },
    });
    expect(costRequest(formOf({ amount: '-1' }), EMPTY, NOW)).toEqual({
      ok: false,
      errors: { amount: [AMOUNT_FORMAT] },
    });
    expect(costRequest(formOf({ amount: '10.5' }), EMPTY, NOW)).toEqual({
      ok: false,
      errors: { amount: [AMOUNT_FORMAT] },
    });
  });

  it('첫 원가는 지난 시각을 UTC ISO 로 보낸다', () => {
    const built = costRequest(
      formOf({ start: 'at', effectiveFrom: '2026-09-01T00:00' }),
      EMPTY,
      NOW,
    );
    expect(built.ok && built.request.effectiveFrom).toBe(
      new Date('2026-09-01T00:00').toISOString(),
    );
  });

  it('시각 지정은 빈 값 · 1970 이전 · 지금 + 5분 뒤를 막는다', () => {
    expect(costRequest(formOf({ start: 'at', effectiveFrom: '' }), EMPTY, NOW)).toEqual({
      ok: false,
      errors: { effectiveFrom: [START_FORMAT] },
    });
    expect(
      costRequest(formOf({ start: 'at', effectiveFrom: '1969-12-31T00:00' }), EMPTY, NOW),
    ).toEqual({ ok: false, errors: { effectiveFrom: [START_TOO_EARLY] } });
    const tooLate = new Date(NOW + 6 * 60 * 1000);
    const local = `${tooLate.getFullYear()}-${String(tooLate.getMonth() + 1).padStart(2, '0')}-${String(tooLate.getDate()).padStart(2, '0')}T${String(tooLate.getHours()).padStart(2, '0')}:${String(tooLate.getMinutes()).padStart(2, '0')}`;
    expect(costRequest(formOf({ start: 'at', effectiveFrom: local }), EMPTY, NOW)).toEqual({
      ok: false,
      errors: { effectiveFrom: [START_IN_FUTURE] },
    });
  });

  it('원가가 있는 유형은 시각 지정 · 다른 통화를 골라도 지금부터 · 고정 통화로 보낸다', () => {
    const current = currentOf(costItem());
    const built = costRequest(
      formOf({ start: 'at', effectiveFrom: '2020-01-01T00:00', currency: 'USD', amount: '13000' }),
      current,
      NOW,
    );
    expect(built).toEqual({
      ok: true,
      request: {
        costType: 'GOODS',
        amount: { amount: 13000, currency: 'KRW' },
        reason: '10월 원단 단가 인상',
      },
    });
  });

  it('사유 — 공백 · 서식 문자만이면 필수, 제어 · 서식 문자 거절, 코드 포인트 200 초과 거절', () => {
    expect(costRequest(formOf({ reason: ' 　​ ' }), EMPTY, NOW)).toEqual({
      ok: false,
      errors: { reason: [REASON_REQUIRED] },
    });
    expect(costRequest(formOf({ reason: '인상\n사유' }), EMPTY, NOW)).toEqual({
      ok: false,
      errors: { reason: [REASON_CHARACTER] },
    });
    expect(costRequest(formOf({ reason: '인상​' }), EMPTY, NOW)).toEqual({
      ok: false,
      errors: { reason: [REASON_CHARACTER] },
    });
    expect(costRequest(formOf({ reason: '😀'.repeat(200) }), EMPTY, NOW).ok).toBe(true);
    expect(costRequest(formOf({ reason: '가'.repeat(201) }), EMPTY, NOW)).toEqual({
      ok: false,
      errors: { reason: [REASON_TOO_LONG] },
    });
  });
});
