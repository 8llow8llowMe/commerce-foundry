import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { ko } from '@/lib/i18n/dictionaries/ko';
import {
  addressErrors,
  draftFrom,
  emptyDraft,
  formatPhone,
  isStaleAddress,
  isUnknownOutcome,
  requiredFields,
  toAddressBody,
} from './address-form';
import type { ShippingAddress } from './types';

const t = ko.account;

const ADDRESS: ShippingAddress = {
  addressId: '101',
  label: null,
  recipientName: '홍길동',
  recipientPhone: '01012345678',
  countryCode: 'KR',
  postalCode: '06236',
  addressLine1: '서울 강남구 테헤란로 1',
  addressLine2: null,
  city: null,
  stateProvince: null,
  isDefault: true,
};

describe('toAddressBody', () => {
  it('앞뒤 공백을 지우고 빈 선택 칸은 null 로, 모든 칸을 싣는다 (PUT 은 보내지 않은 칸을 지운다)', () => {
    const body = toAddressBody(
      {
        ...emptyDraft('kr'),
        label: '  ',
        recipientName: ' 홍길동 ',
        recipientPhone: ' 010-1234-5678 ',
        postalCode: ' 06236 ',
        addressLine1: ' 서울 ',
        addressLine2: ' 101호 ',
      },
      false,
    );
    expect(body).toEqual({
      label: null,
      recipientName: '홍길동',
      recipientPhone: '010-1234-5678',
      countryCode: 'KR',
      postalCode: '06236',
      addressLine1: '서울',
      addressLine2: '101호',
      city: null,
      stateProvince: null,
    });
  });

  it('국내는 화면에 없는 도시 · 주 값을 보내지 않는다 (해외에서 국내로 바꾼 경우)', () => {
    const draft = { ...emptyDraft('KR'), city: 'Tokyo', stateProvince: 'Tokyo-to' };
    expect(toAddressBody(draft, false)).toMatchObject({ city: null, stateProvince: null });
    expect(toAddressBody({ ...draft, countryCode: 'JP' }, false)).toMatchObject({
      city: 'Tokyo',
      stateProvince: 'Tokyo-to',
    });
  });

  it('기본 여부는 추가(POST)에서만 싣는다', () => {
    const draft = { ...emptyDraft('KR'), isDefault: true };
    expect(toAddressBody(draft, true).isDefault).toBe(true);
    expect(toAddressBody(draft, false)).not.toHaveProperty('isDefault');
  });

  it('저장된 주소를 폼 값으로 되돌리면 null 칸은 빈 문자열이다', () => {
    const draft = draftFrom(ADDRESS);
    expect(draft.label).toBe('');
    expect(toAddressBody(draft, false)).toMatchObject({ label: null, addressLine2: null });
  });
});

describe('requiredFields', () => {
  it('국내는 우편번호, 해외는 도시가 필수다', () => {
    expect(requiredFields('KR').has('postalCode')).toBe(true);
    expect(requiredFields('KR').has('city')).toBe(false);
    expect(requiredFields('US').has('city')).toBe(true);
    expect(requiredFields('US').has('postalCode')).toBe(false);
  });
});

describe('addressErrors', () => {
  it('검증 오류는 칸마다 사전 문구로, 사전에 없으면 서버 문구다', () => {
    const cause = new ApiError('validation', 400, 'AUTH_100', null, [
      { code: 'AUTH_125', field: 'postalCode', message: '서버' },
      { code: 'AUTH_199', field: 'city', message: '서버 도시 문구' },
    ]);
    const errors = addressErrors(cause, t);
    expect(errors.postalCode).toEqual([t.errors.AUTH_125]);
    expect(errors.city).toEqual(['서버 도시 문구']);
    expect(errors.form).toEqual([]);
  });

  it('모르는 칸의 오류는 폼 문구로 알린다', () => {
    const cause = new ApiError('validation', 400, 'AUTH_100', null, [
      { code: 'AUTH_100', field: 'unknown', message: '서버' },
    ]);
    expect(addressErrors(cause, t).form).toEqual([t.address.failed]);
  });

  it('상한(021) · 회원 잠금(023)은 코드 문구, 연결 실패는 기본 문구다', () => {
    expect(addressErrors(new ApiError('conflict', 409, 'AUTH_021', 'x'), t).form).toEqual([
      t.errors.AUTH_021,
    ]);
    expect(addressErrors(new ApiError('conflict', 409, 'AUTH_023', 'x'), t).form).toEqual([
      t.errors.AUTH_023,
    ]);
    expect(addressErrors(new ApiError('network', null, null, null), t).form).toEqual([
      t.address.failed,
    ]);
    expect(addressErrors(new Error('x'), t).form).toEqual([t.address.failed]);
  });
});

describe('isStaleAddress', () => {
  it('없어진 주소(020) · 동시 변경(022)이면 다시 읽는다', () => {
    expect(isStaleAddress(new ApiError('notFound', 404, 'AUTH_020', null))).toBe(true);
    expect(isStaleAddress(new ApiError('conflict', 409, 'AUTH_022', null))).toBe(true);
    expect(isStaleAddress(new ApiError('conflict', 409, 'AUTH_023', null))).toBe(false);
  });
});

describe('isUnknownOutcome', () => {
  it('502 · 504 · 연결 끊김이면 결과를 모른다 — 다시 저장하지 않고 목록을 읽는다', () => {
    expect(isUnknownOutcome(new ApiError('unknownOutcome', 504, null, null))).toBe(true);
    expect(isUnknownOutcome(new ApiError('network', null, null, null))).toBe(true);
    expect(isUnknownOutcome(new ApiError('unavailable', 503, null, null))).toBe(false);
    expect(isUnknownOutcome(new Error('x'))).toBe(false);
  });
});

describe('formatPhone', () => {
  it.each([
    ['01012345678', '010-1234-5678'],
    ['0101234567', '010-123-4567'],
    ['0212345678', '02-1234-5678'],
    ['021234567', '02-123-4567'],
    ['0311234567', '031-123-4567'],
  ])('국내 %s → %s', (phone, formatted) => {
    expect(formatPhone(phone, 'KR')).toBe(formatted);
  });

  it('해외 번호와 규칙 밖 값은 그대로 둔다', () => {
    expect(formatPhone('+12025550123', 'US')).toBe('+12025550123');
    expect(formatPhone('15881234', 'KR')).toBe('15881234');
  });
});
