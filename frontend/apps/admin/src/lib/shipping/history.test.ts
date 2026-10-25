import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { compareHistory, historyLoadFailure, historyPagePath } from './history';
import { historyItem, krw, snapshot } from './history-fixtures';

const fieldOf = (item: ReturnType<typeof historyItem>, key: string) => {
  const field = compareHistory(item).fields.find((f) => f.key === key);
  if (!field) throw new Error(key);
  return field;
};

describe('compareHistory', () => {
  it('처음 만든 교체(before null)는 처음 설정 — 설정 값만, 강조 없이 보인다', () => {
    const result = compareHistory(historyItem({ before: null, after: snapshot() }));
    expect(result.kind).toBe('initial');
    expect(result.fields.map((f) => [f.label, f.before, f.after, f.changed])).toEqual([
      ['기본 배송비', null, [{ text: '3,000원', changed: false }], false],
      ['무료배송 기준', null, [{ text: '50,000원', changed: false }], false],
      ['도서산간 구간', null, [{ text: '구간 가 10000~10999 +3,000원', changed: false }], false],
    ]);
  });

  it('값이 그대로인 교체는 version 이 달라도 값 변경 없음이다', () => {
    const result = compareHistory(historyItem());
    expect(result.kind).toBe('unchanged');
    expect(result.fields.every((f) => !f.changed)).toBe(true);
  });

  it('바뀐 금액 칸만 강조한다', () => {
    const item = historyItem({ after: snapshot({ baseFee: krw(4000), version: 2 }) });
    expect(compareHistory(item).kind).toBe('changed');
    expect(fieldOf(item, 'baseFee')).toMatchObject({
      changed: true,
      before: [{ text: '3,000원', changed: true }],
      after: [{ text: '4,000원', changed: true }],
    });
    expect(fieldOf(item, 'freeShippingThreshold').changed).toBe(false);
    expect(fieldOf(item, 'remoteAreas').changed).toBe(false);
  });

  it('무료배송 기준을 없애면 없음으로 보이고 바뀐 칸이다', () => {
    const item = historyItem({ after: snapshot({ freeShippingThreshold: null }) });
    expect(fieldOf(item, 'freeShippingThreshold')).toMatchObject({
      changed: true,
      after: [{ text: '없음', changed: true }],
    });
  });

  it('통화만 달라도 바뀐 금액이다', () => {
    const item = historyItem({ after: snapshot({ baseFee: { amount: 3000, currency: 'USD' } }) });
    expect(fieldOf(item, 'baseFee').changed).toBe(true);
  });

  it('도서산간 구간은 반대쪽에 없는 줄만 강조한다', () => {
    const kept = {
      name: '구간 가',
      postalCodeFrom: '10000',
      postalCodeTo: '10999',
      surcharge: krw(3000),
    };
    const area = (amount: number) => ({
      name: '구간 나',
      postalCodeFrom: '20000',
      postalCodeTo: '20999',
      surcharge: krw(amount),
    });
    const item = historyItem({
      before: snapshot({ remoteAreas: [kept, area(5000)] }),
      after: snapshot({ remoteAreas: [kept, area(6000)] }),
    });
    const areas = fieldOf(item, 'remoteAreas');
    expect(areas.changed).toBe(true);
    expect(areas.before).toEqual([
      { text: '구간 가 10000~10999 +3,000원', changed: false },
      { text: '구간 나 20000~20999 +5,000원', changed: true },
    ]);
    expect(areas.after).toEqual([
      { text: '구간 가 10000~10999 +3,000원', changed: false },
      { text: '구간 나 20000~20999 +6,000원', changed: true },
    ]);
  });

  it('구간을 모두 지우면 이후는 없음으로 강조한다', () => {
    const item = historyItem({ after: snapshot({ remoteAreas: [] }) });
    const areas = fieldOf(item, 'remoteAreas');
    expect(areas.changed).toBe(true);
    expect(areas.before).toEqual([{ text: '구간 가 10000~10999 +3,000원', changed: true }]);
    expect(areas.after).toEqual([{ text: '없음', changed: true }]);
  });
});

describe('historyPagePath', () => {
  it('국내 이력 경로에 page 와 서버 상한 size(20)를 싣는다', () => {
    expect(historyPagePath(2)).toBe(
      '/api/v1/admin/shipping-policies/domestic/histories?page=2&size=20',
    );
  });
});

describe('historyLoadFailure', () => {
  it('401 · 403 은 안내 상태, 그 밖은 다시 불러오기다', () => {
    expect(historyLoadFailure(new ApiError('unauthorized', 401, 'GATEWAY_001', null))).toEqual({
      kind: 'denied',
      denied: 'unauthorized',
    });
    expect(historyLoadFailure(new ApiError('forbidden', 403, 'GATEWAY_002', null))).toEqual({
      kind: 'denied',
      denied: 'forbidden',
    });
    expect(historyLoadFailure(new ApiError('server', 400, 'DELIVERY_138', '크기'))).toEqual({
      kind: 'failed',
    });
    expect(historyLoadFailure(new ApiError('network', null, null, null))).toEqual({
      kind: 'failed',
    });
    expect(historyLoadFailure(new Error('x'))).toEqual({ kind: 'failed' });
  });
});
