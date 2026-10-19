import { describe, expect, it } from 'vitest';
import { businessVerificationUrl, parseBusinessInfo } from './business-info';

describe('parseBusinessInfo', () => {
  it('BUSINESS_* 를 읽고 앞뒤 공백을 지운다', () => {
    expect(
      parseBusinessInfo({
        BUSINESS_NAME: ' 테스트상점 ',
        BUSINESS_REPRESENTATIVE: '홍길동',
        BUSINESS_REGISTRATION_NUMBER: '000-00-00000',
        BUSINESS_MAIL_ORDER_NUMBER: '제0000-테스트-0000호',
        BUSINESS_ADDRESS: '서울특별시 어딘가',
        BUSINESS_CONTACT: '00-0000-0000',
      }),
    ).toEqual({
      name: '테스트상점',
      representative: '홍길동',
      registrationNumber: '000-00-00000',
      mailOrderNumber: '제0000-테스트-0000호',
      address: '서울특별시 어딘가',
      contact: '00-0000-0000',
    });
  });

  it('없거나 빈 값은 null 이다 (로컬 · 미설정 환경)', () => {
    expect(parseBusinessInfo({ BUSINESS_NAME: '   ' })).toEqual({
      name: null,
      representative: null,
      registrationNumber: null,
      mailOrderNumber: null,
      address: null,
      contact: null,
    });
  });
});

describe('businessVerificationUrl', () => {
  it('사업자등록번호 숫자 10자리로 공정위 정보 공개 링크를 만든다', () => {
    expect(businessVerificationUrl('000-00-00001')).toBe(
      'https://www.ftc.go.kr/bizCommPop.do?wrkr_no=0000000001',
    );
  });

  it('없거나 자릿수가 다르면 링크를 만들지 않는다', () => {
    expect(businessVerificationUrl(null)).toBeNull();
    expect(businessVerificationUrl('123-45')).toBeNull();
  });
});
