import { describe, expect, it } from 'vitest';
import {
  CONTROL_CHAR,
  COUNTRY_FORMAT,
  diffFields,
  EMAIL_FORMAT,
  REQUIRED,
  storeProfileForm,
  storeProfileRequest,
  TOO_LONG,
} from './form';
import type { StoreProfile } from './types';

// 실정보가 아닌 자리 값만 쓴다(공개 저장소)
const PROFILE: StoreProfile = {
  storeName: '테스트몰',
  companyName: '테스트상점',
  representativeName: '홍길동',
  countryCode: 'KR',
  businessRegistrationNumber: '000-00-00001',
  mailOrderRegistrationNumber: null,
  address: '테스트시 테스트로 1',
  customerServiceEmail: 'help@example.com',
  customerServicePhone: '00-0000-0000',
  customerServiceHours: null,
  version: 4,
};

describe('storeProfileForm', () => {
  it('미등록이면 빈 폼(version null)', () => {
    const form = storeProfileForm(null);
    expect(form.version).toBeNull();
    expect(form.storeName).toBe('');
    expect(form.countryCode).toBe('');
  });

  it('서버 값으로 채우고 빈 선택 칸은 빈 글자', () => {
    const form = storeProfileForm(PROFILE);
    expect(form.version).toBe(4);
    expect(form.mailOrderRegistrationNumber).toBe('');
  });
});

describe('storeProfileRequest', () => {
  it('앞뒤 공백을 빼고 빈 선택 칸은 null, expectedVersion 은 조회한 version', () => {
    const result = storeProfileRequest({
      ...storeProfileForm(PROFILE),
      storeName: '  테스트몰 ',
      customerServicePhone: '  ',
    });
    expect(result).toEqual({
      ok: true,
      request: {
        ...PROFILE,
        version: undefined,
        customerServicePhone: null,
        expectedVersion: 4,
      },
    });
    if (result.ok) expect(result.request).not.toHaveProperty('version');
  });

  it('처음 만들기는 expectedVersion null', () => {
    const result = storeProfileRequest({ ...storeProfileForm(PROFILE), version: null });
    expect(result.ok && result.request.expectedVersion).toBeNull();
  });

  it('필수 · 길이 · 국가 코드 · 이메일 · 제어 문자를 막는다', () => {
    const result = storeProfileRequest({
      ...storeProfileForm(PROFILE),
      companyName: ' ',
      storeName: 'a'.repeat(101),
      countryCode: 'kr',
      customerServiceEmail: 'help',
      address: '줄\n바꿈',
    });
    expect(result).toEqual({
      ok: false,
      errors: {
        companyName: [REQUIRED('상호')],
        storeName: [TOO_LONG('쇼핑몰 이름', 100)],
        countryCode: [COUNTRY_FORMAT],
        customerServiceEmail: [EMAIL_FORMAT],
        address: [CONTROL_CHAR],
      },
    });
  });

  it('사업자 식별 번호는 국가별 형식을 검사하지 않는다', () => {
    const result = storeProfileRequest({
      ...storeProfileForm(PROFILE),
      countryCode: 'US',
      businessRegistrationNumber: 'EIN 00-0000000',
    });
    expect(result.ok).toBe(true);
  });
});

describe('diffFields', () => {
  it('보낼 값 기준으로 다른 칸만 돌려준다(공백 차이는 같은 값)', () => {
    const form = { ...storeProfileForm(PROFILE), storeName: ' 테스트몰 ', address: '새 주소' };
    expect(diffFields(form, { ...PROFILE, customerServiceHours: '평일' })).toEqual([
      { key: 'address', label: '공개 주소', mine: '새 주소', latest: '테스트시 테스트로 1' },
      { key: 'customerServiceHours', label: '고객센터 운영 시간', mine: '', latest: '평일' },
    ]);
  });

  it('최신 값이 없으면(미등록) 입력한 칸이 모두 다르다', () => {
    const diffs = diffFields(storeProfileForm(PROFILE), null);
    expect(diffs.map((diff) => diff.key)).toContain('storeName');
    expect(diffs.every((diff) => diff.latest === '')).toBe(true);
  });
});
