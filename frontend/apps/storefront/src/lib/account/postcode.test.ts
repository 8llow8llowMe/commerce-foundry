import { describe, expect, it } from 'vitest';
import { postcodeResult, type PostcodeData } from './postcode';

const DATA: PostcodeData = {
  zonecode: '06236',
  roadAddress: '서울 강남구 테헤란로 152',
  jibunAddress: '서울 강남구 역삼동 737',
  userSelectedType: 'R',
  buildingName: '강남파이낸스센터',
  apartment: 'N',
};

describe('postcodeResult', () => {
  it('사용자가 고른 쪽(도로명 · 지번) 주소와 우편번호를 쓴다', () => {
    expect(postcodeResult(DATA)).toEqual({
      postalCode: '06236',
      address: '서울 강남구 테헤란로 152',
    });
    expect(postcodeResult({ ...DATA, userSelectedType: 'J' }).address).toBe(
      '서울 강남구 역삼동 737',
    );
  });

  it('도로명 공동주택이면 건물명을 괄호로 붙인다', () => {
    expect(postcodeResult({ ...DATA, apartment: 'Y', buildingName: '래미안' }).address).toBe(
      '서울 강남구 테헤란로 152 (래미안)',
    );
  });
});
