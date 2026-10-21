import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { accessDeniedOf, fieldKey } from './api-errors';

describe('accessDeniedOf', () => {
  it('401 · 403 만 로그인 · 권한 안내로 가른다', () => {
    expect(accessDeniedOf(new ApiError('unauthorized', 401, 'GATEWAY_001', null))).toBe(
      'unauthorized',
    );
    expect(accessDeniedOf(new ApiError('forbidden', 403, 'GATEWAY_005', null))).toBe('forbidden');
    expect(accessDeniedOf(new ApiError('server', 500, null, null))).toBeNull();
    expect(accessDeniedOf(new Error('boom'))).toBeNull();
  });
});

describe('fieldKey', () => {
  it('목록 첨자를 점으로 잇고, 금액의 amount · currency 는 금액 칸 하나로 모은다', () => {
    expect(fieldKey('baseFee.amount')).toBe('baseFee');
    expect(fieldKey('freeShippingThreshold.currency')).toBe('freeShippingThreshold');
    expect(fieldKey('remoteAreas[0].postalCodeFrom')).toBe('remoteAreas.0.postalCodeFrom');
    expect(fieldKey('remoteAreas[2].surcharge.amount')).toBe('remoteAreas.2.surcharge');
    expect(fieldKey('zones[1].rates[0].maxWeightGrams')).toBe('zones.1.rates.0.maxWeightGrams');
  });

  it('목록 원소 오류는 그 목록 칸에 붙인다', () => {
    expect(fieldKey('zones[0].countryCodes[2]')).toBe('zones.0.countryCodes');
    expect(fieldKey('remoteAreas[1]')).toBe('remoteAreas');
    expect(fieldKey('remoteAreas')).toBe('remoteAreas');
  });
});
