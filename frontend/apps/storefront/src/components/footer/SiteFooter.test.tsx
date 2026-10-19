import type { FooterLink } from '@cf/ui';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { parseBusinessInfo } from '@/lib/business-info';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
import { SiteFooter } from './SiteFooter';

// 실정보가 아닌 자리 값만 쓴다 (공개 저장소)
const LINKS: FooterLink[] = [
  { label: '약관', href: '/terms' },
  { label: '방침', href: '/privacy', emphasis: true },
];
const FULL = parseBusinessInfo({
  BUSINESS_NAME: '테스트상점',
  BUSINESS_REPRESENTATIVE: '홍길동',
  BUSINESS_REGISTRATION_NUMBER: '000-00-00001',
  BUSINESS_MAIL_ORDER_NUMBER: '제0000-테스트-0000호',
  BUSINESS_ADDRESS: '테스트시 테스트로 1',
  BUSINESS_CONTACT: '00-0000-0000',
});

const render = (business = FULL, links = LINKS, labels = ko.footer) =>
  renderToStaticMarkup(
    <SiteFooter brandName="ACME" links={links} business={business} labels={labels} year={2026} />,
  );

describe('SiteFooter', () => {
  it('사업자 정보 6줄과 공정위 확인 링크(새 창)를 보인다', () => {
    const html = render();
    for (const label of [
      '상호',
      '대표',
      '사업자등록번호',
      '통신판매업 신고번호',
      '주소',
      '고객센터',
    ]) {
      expect(html).toContain(`<dt>${label}</dt>`);
    }
    expect(html).toContain('<dd class="text-fg">테스트상점</dd>');
    expect(html).toMatch(
      /<a href="https:\/\/www.ftc.go.kr\/bizCommPop.do\?wrkr_no=0000000001" target="_blank" rel="noopener noreferrer"/,
    );
  });

  it('값이 없는 줄은 빼고, 모두 없으면 사업자 정보 블록과 확인 링크를 그리지 않는다', () => {
    const partial = render({ ...FULL, address: null, registrationNumber: null });
    expect(partial).not.toContain('<dt>주소</dt>');
    expect(partial).not.toContain('ftc.go.kr');

    const empty = render(parseBusinessInfo({}));
    expect(empty).not.toContain('aria-label="사업자 정보"');
    expect(empty.replace(/<!-- -->/g, '')).toContain('© 2026 ACME');
  });

  it('고객 안내 링크는 데이터대로, emphasis 는 굵게', () => {
    const html = render();
    expect(html).toMatch(/<a class="hover:underline text-muted-fg" href="\/terms">약관<\/a>/);
    expect(html).toMatch(/<a class="hover:underline font-semibold" href="\/privacy">방침<\/a>/);
    expect(render(FULL, [])).not.toContain('aria-label="고객 안내"');
  });

  it('영문 스토어는 사업자 정보 항목 이름을 영문 사전으로 보인다', () => {
    const html = render(FULL, LINKS, en.footer);
    expect(html).toContain('aria-label="Company information"');
    expect(html).toContain('<dt>Business registration no.</dt>');
    expect(html).not.toContain('사업자등록번호');
  });
});
