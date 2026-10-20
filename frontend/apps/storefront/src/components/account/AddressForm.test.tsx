import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { emptyDraft } from '@/lib/account/address-form';
import { countryOptions } from '@/lib/account/countries';
import { AddressForm } from './AddressForm';

const render = (countryCode: string, firstAddress = false) =>
  renderToStaticMarkup(
    <AddressForm
      title="새 배송지"
      initial={emptyDraft(countryCode)}
      countries={countryOptions(['US'], 'ko')}
      showDefault
      firstAddress={firstAddress}
      onSave={async () => null}
      onCancel={() => {}}
    />,
  );

const input = (html: string, name: string) =>
  html.match(new RegExp(`<input[^>]*name="${name}"[^>]*>`))?.[0];

describe('AddressForm', () => {
  it('국내는 우편번호 필수 · 주소 검색이 있고 도시 칸이 없다', () => {
    const html = render('KR');
    expect(input(html, 'postalCode')).toContain('required');
    expect(html).toContain('주소 검색');
    expect(input(html, 'city')).toBeUndefined();
    expect(html).toContain('기본 배송지로 저장');
  });

  it('해외는 도시가 필수이고 우편번호는 선택, 주소 검색이 없다', () => {
    const html = render('US');
    expect(input(html, 'city')).toContain('required');
    expect(input(html, 'postalCode')).not.toContain('required');
    expect(html).not.toContain('주소 검색');
    expect(html).toContain('<option value="US" selected="">미국</option>');
  });

  it('첫 배송지는 기본 지정 체크 대신 안내를 보인다', () => {
    const html = render('KR', true);
    expect(html).toContain('첫 배송지는 기본 배송지가 됩니다.');
    expect(html).not.toContain('기본 배송지로 저장');
  });
});
