import { describe, expect, it } from 'vitest';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
import { businessVerificationUrl, registrationLabel } from './business-info';

describe('businessVerificationUrl', () => {
  it('한국은 사업자등록번호 숫자 10자리로 공정위 정보 공개 링크를 만든다', () => {
    expect(businessVerificationUrl('KR', '000-00-00001')).toBe(
      'https://www.ftc.go.kr/bizCommPop.do?wrkr_no=0000000001',
    );
  });

  it('한국이어도 자릿수가 다르면 링크를 만들지 않는다', () => {
    expect(businessVerificationUrl('KR', '123-45')).toBeNull();
  });

  it('규칙이 없는 국가는 같은 숫자여도 한국 링크를 만들지 않는다', () => {
    expect(businessVerificationUrl('US', '000-00-00001')).toBeNull();
    expect(businessVerificationUrl('JP', '0000000001')).toBeNull();
  });
});

describe('registrationLabel', () => {
  it('국가 이름이 있으면 그것, 없으면 일반 이름', () => {
    expect(registrationLabel(ko.footer, 'KR')).toBe('사업자등록번호');
    expect(registrationLabel(ko.footer, 'US')).toBe('사업자 번호');
    expect(registrationLabel(en.footer, 'KR')).toBe('Business registration no.');
    expect(registrationLabel(en.footer, 'US')).toBe('Business ID');
  });
});
