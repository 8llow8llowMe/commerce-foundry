import { describe, expect, it } from 'vitest';
import { countryOptions, defaultCountry, policyCountryCodes } from './countries';

describe('countryOptions', () => {
  it('국내가 맨 앞이고 나머지는 그 언어의 이름 순이며, 중복 · 형식 밖 코드는 뺀다', () => {
    const options = countryOptions(['US', 'JP', 'KR', 'US', 'usa', ''], 'ko');
    expect(options.map((option) => option.code)).toEqual(['KR', 'US', 'JP']);
    expect(options[0]!.name).toBe('대한민국');
  });

  it('영문 이름으로 정렬한다', () => {
    const options = countryOptions(['US', 'JP', 'CA'], 'en');
    expect(options.map((option) => option.name)).toEqual([
      'South Korea',
      'Canada',
      'Japan',
      'United States',
    ]);
  });
});

describe('defaultCountry', () => {
  it('국문은 KR, 영문은 US 이고 선택지에 없으면 첫 선택지다', () => {
    expect(defaultCountry(countryOptions(['US'], 'ko'), 'ko')).toBe('KR');
    expect(defaultCountry(countryOptions(['US'], 'en'), 'en')).toBe('US');
    expect(defaultCountry(countryOptions([], 'en'), 'en')).toBe('KR');
  });
});

describe('policyCountryCodes', () => {
  it('국가 그룹의 국가 코드를 모은다', () => {
    const policy = {
      zones: [
        { name: '아시아', countryCodes: ['JP', 'SG'], rates: [] },
        { name: '북미', countryCodes: ['US'], rates: [] },
      ],
    };
    expect(policyCountryCodes(policy)).toEqual(['JP', 'SG', 'US']);
  });

  it('해외 배송을 끄면(null) · 모양이 다르면 빈 목록이다', () => {
    expect(policyCountryCodes(null)).toEqual([]);
    expect(policyCountryCodes({ zones: 'x' })).toEqual([]);
    expect(policyCountryCodes({ zones: [null, { countryCodes: [1, 'JP'] }] })).toEqual(['JP']);
  });
});
