import { describe, expect, it } from 'vitest';
import {
  domesticForm,
  domesticRequest,
  internationalForm,
  internationalRequest,
  MONEY_FORMAT_ERROR,
  splitCountryCodes,
  WEIGHT_FORMAT_ERROR,
} from './policy-form';
import type { DomesticPolicy, InternationalPolicy } from './types';

const DOMESTIC: DomesticPolicy = {
  shippingPolicyId: '1',
  baseFee: { amount: 3000, currency: 'KRW' },
  freeShippingThreshold: { amount: 50000, currency: 'KRW' },
  remoteAreas: [
    {
      remoteAreaId: '11',
      name: '제주',
      postalCodeFrom: '63000',
      postalCodeTo: '63644',
      surcharge: { amount: 3000, currency: 'KRW' },
    },
  ],
  version: 4,
  updatedAt: '2026-10-21T01:00:00Z',
};

const INTERNATIONAL: InternationalPolicy = {
  shippingPolicyId: '2',
  baseFee: { amount: 1500, currency: 'USD' },
  freeShippingThreshold: null,
  customsNotice: null,
  zones: [
    {
      shippingZoneId: '21',
      name: 'North America',
      countryCodes: ['CA', 'US'],
      rates: [
        { shippingZoneRateId: '211', maxWeightGrams: 500, fee: { amount: 2050, currency: 'USD' } },
      ],
    },
  ],
  version: 2,
  updatedAt: '2026-10-21T01:00:00Z',
};

describe('국내 정책 폼', () => {
  it('정책이 없으면 처음 만들기(version null) 빈 폼이다', () => {
    expect(domesticForm(null)).toMatchObject({ version: null, remoteAreas: [], baseFee: '' });
  });

  it('읽은 정책을 폼으로 바꾸고, 그대로 보내면 같은 값 · 같은 version 이 된다', () => {
    const form = domesticForm(DOMESTIC);
    expect(form).toMatchObject({
      currency: 'KRW',
      baseFee: '3000',
      freeShippingThreshold: '50000',
    });
    expect(domesticRequest(form)).toEqual({
      ok: true,
      request: {
        baseFee: { amount: 3000, currency: 'KRW' },
        freeShippingThreshold: { amount: 50000, currency: 'KRW' },
        remoteAreas: [
          {
            name: '제주',
            postalCodeFrom: '63000',
            postalCodeTo: '63644',
            surcharge: { amount: 3000, currency: 'KRW' },
          },
        ],
        version: 4,
      },
    });
  });

  it('무료배송 기준을 비우면 null(무료배송 없음), 빈 기본 배송비는 amount null 로 서버 검증에 맡긴다', () => {
    const form = { ...domesticForm(DOMESTIC), baseFee: '', freeShippingThreshold: ' ' };
    const built = domesticRequest(form);
    expect(built.ok && built.request.freeShippingThreshold).toBeNull();
    expect(built.ok && built.request.baseFee).toEqual({ amount: null, currency: 'KRW' });
  });

  it('금액 형식이 틀리면 보내지 않고 그 칸에 오류를 붙인다', () => {
    const form = domesticForm(DOMESTIC);
    form.remoteAreas[0]!.surcharge = '3천';
    expect(domesticRequest({ ...form, baseFee: '-1' })).toEqual({
      ok: false,
      errors: { baseFee: [MONEY_FORMAT_ERROR], 'remoteAreas.0.surcharge': [MONEY_FORMAT_ERROR] },
    });
  });
});

describe('해외 정책 폼', () => {
  it('정책이 없으면 빈 그룹 하나 · 빈 구간 하나로 시작한다', () => {
    const form = internationalForm(null);
    expect(form.version).toBeNull();
    expect(form.zones).toHaveLength(1);
    expect(form.zones[0]!.rates).toHaveLength(1);
  });

  it('USD 금액은 센트로 주고받고, 국가 코드는 목록으로 보낸다 — remoteAreas 는 보내지 않는다', () => {
    const form = internationalForm(INTERNATIONAL);
    expect(form.baseFee).toBe('15.00');
    expect(form.zones[0]!.countryCodes).toBe('CA, US');
    const built = internationalRequest({ ...form, customsNotice: '  관세는 수령인 부담  ' });
    expect(built).toEqual({
      ok: true,
      request: {
        baseFee: { amount: 1500, currency: 'USD' },
        freeShippingThreshold: null,
        customsNotice: '관세는 수령인 부담',
        zones: [
          {
            name: 'North America',
            countryCodes: ['CA', 'US'],
            rates: [{ maxWeightGrams: 500, fee: { amount: 2050, currency: 'USD' } }],
          },
        ],
        version: 2,
      },
    });
    expect(built.ok && 'remoteAreas' in built.request).toBe(false);
  });

  it('무게가 정수가 아니면 그 칸에 오류를 붙인다', () => {
    const form = internationalForm(INTERNATIONAL);
    form.zones[0]!.rates[0]!.maxWeightGrams = '0.5kg';
    expect(internationalRequest(form)).toEqual({
      ok: false,
      errors: { 'zones.0.rates.0.maxWeightGrams': [WEIGHT_FORMAT_ERROR] },
    });
  });
});

describe('splitCountryCodes', () => {
  it('쉼표 · 공백으로 나누고 대문자로 맞춘다', () => {
    expect(splitCountryCodes(' us, ca  jp,,')).toEqual(['US', 'CA', 'JP']);
    expect(splitCountryCodes('')).toEqual([]);
  });
});
