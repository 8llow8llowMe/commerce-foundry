import type { NavItem } from '@cf/ui';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { LanguageLink } from '@/components/i18n/LanguageLinks';
import { LocaleProvider } from '@/components/i18n/LocaleProvider';
import { ko } from '@/lib/i18n/dictionaries/ko';
import { SiteHeader } from './SiteHeader';

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }) }));

// 브랜드와 무관한 메뉴로 확인한다 — 컴포넌트가 특정 브랜드 메뉴를 갖고 있지 않아야 한다.
const NAV: NavItem[] = [
  { label: 'ALPHA', description: '첫째 설명', href: '/alpha', primary: true, shortcut: true },
  { label: 'BETA', description: '둘째 설명', href: '/beta', primary: true },
  { label: 'GAMMA', description: '셋째 설명', href: '/gamma' },
];

const render = (navigation: NavItem[] = NAV, languages: LanguageLink[] = [], signedIn = false) =>
  renderToStaticMarkup(
    <SiteHeader
      brandName="ACME"
      navigation={navigation}
      labels={ko.header}
      languages={languages}
      signedIn={signedIn}
    />,
  );

/** aria-label 로 찾은 링크 태그 */
const linkLabelled = (html: string, label: string) =>
  html.match(new RegExp(`<a [^>]*aria-label="${label}"[^>]*>`))?.[0] ?? '';

/** aria-label 로 찾은 nav 블록의 마크업 */
const navBlock = (html: string, label: string) =>
  html.match(new RegExp(`<nav aria-label="${label}"[\\s\\S]*?</nav>`))?.[0] ?? '';

describe('SiteHeader', () => {
  it('가운데 워드마크는 홈 링크이고, 검색 · 장바구니 링크에 라벨이 있다', () => {
    const html = render();
    expect(html).toMatch(/<a [^>]*href="\/"[^>]*>ACME<\/a>/);
    expect(html).toMatch(
      /<a [^>]*href="\/search"[^>]*aria-label="검색"|aria-label="검색"[^>]*href="\/search"/,
    );
    expect(html).toMatch(/aria-label="장바구니"/);
  });

  it('로그아웃 상태면 계정 아이콘은 로그인으로, 메뉴 계정 줄은 로그인 · 회원가입이다', () => {
    const html = render();
    expect(linkLabelled(html, '로그인')).toContain('href="/login"');
    const account = navBlock(html, '계정');
    expect(account).toContain('href="/login"');
    expect(account).toContain('href="/signup"');
    expect(account).not.toContain('로그아웃');
  });

  it('로그인 상태면 계정 아이콘은 마이페이지로, 메뉴 계정 줄은 마이페이지 · 로그아웃 버튼이다', () => {
    const html = render(NAV, [], true);
    expect(linkLabelled(html, '마이페이지')).toContain('href="/mypage"');
    const account = navBlock(html, '계정');
    expect(account).toContain('href="/mypage"');
    expect(account).toMatch(/<button type="button"[^>]*>로그아웃<\/button>/);
    expect(account).not.toContain('href="/login"');
  });

  it('전체 메뉴 버튼은 닫힌 상태로 시작하고 패널을 가리킨다', () => {
    const html = render();
    const button = html.match(/<button[^>]*aria-label="전체 메뉴 열기"[^>]*>/)?.[0] ?? '';
    expect(button).toContain('aria-expanded="false"');
    expect(button).toContain('aria-haspopup="dialog"');
    const controls = button.match(/aria-controls="([^"]+)"/)?.[1];
    expect(controls).toBeTruthy();
    expect(html).toMatch(new RegExp(`<dialog[^>]*id="${controls}"`));
    // 서버 렌더에서 패널은 열려 있지 않다
    expect(html).not.toMatch(/<dialog[^>]* open/);
  });

  it('전체 메뉴 패널은 모든 항목을 대제목 + 설명으로 보여 준다', () => {
    const panel = navBlock(render(), '전체 메뉴');
    for (const item of NAV) {
      expect(panel).toContain(`href="${item.href}"`);
      expect(panel).toContain(item.label);
      expect(panel).toContain(item.description);
    }
  });

  it('PC 1차 메뉴는 primary 만, 모바일 바로가기는 shortcut 만 보여 준다', () => {
    const html = render();
    const primary = navBlock(html, '주 메뉴');
    expect(primary).toContain('ALPHA');
    expect(primary).toContain('BETA');
    expect(primary).not.toContain('GAMMA');

    const shortcuts = navBlock(html, '바로가기');
    expect(shortcuts).toContain('ALPHA');
    expect(shortcuts).not.toContain('BETA');
  });

  it('메뉴 데이터만 바꾸면 컴포넌트 수정 없이 메뉴가 바뀐다', () => {
    const html = render([{ label: 'ONLY', description: '하나뿐', href: '/only' }]);
    expect(navBlock(html, '전체 메뉴')).toContain('ONLY');
    expect(html).not.toContain('ALPHA');
    // primary · shortcut 이 없으면 아래 줄 nav 를 그리지 않는다
    expect(html).not.toContain('aria-label="주 메뉴"');
    expect(html).not.toContain('aria-label="바로가기"');
  });

  it('언어가 둘 이상이면 메뉴 패널에 언어 전환 링크를 두고 지금 언어를 표시한다', () => {
    const languages: LanguageLink[] = [
      { locale: 'ko', name: '한국어', href: '/', target: { origin: '', prefix: '' } },
      { locale: 'en', name: 'English', href: '/en', target: { origin: '', prefix: '/en' } },
    ];
    expect(render(NAV, languages.slice(0, 1))).not.toContain('aria-label="언어 선택"');
    const html = render(NAV, languages);
    const block = navBlock(html, '언어 선택');
    const link = (name: string) => block.match(new RegExp(`<a [^>]*>${name}</a>`))?.[0] ?? '';
    expect(link('English')).toMatch(/href="\/en"/);
    expect(link('English')).toMatch(/hreflang="en"/i);
    expect(link('English')).not.toContain('aria-current');
    expect(link('한국어')).toContain('aria-current="true"');
  });

  it('영문 스토어에서는 앱 경로 링크에 /en 접두어가 붙는다', () => {
    const html = renderToStaticMarkup(
      <LocaleProvider locale="en" prefix="/en" dict={ko}>
        <SiteHeader
          brandName="ACME"
          navigation={NAV}
          labels={ko.header}
          languages={[]}
          signedIn={false}
        />
      </LocaleProvider>,
    );
    expect(html).toMatch(/<a [^>]*href="\/en"[^>]*>ACME<\/a>/);
    expect(navBlock(html, '주 메뉴')).toContain('href="/en/alpha"');
    expect(html).toContain('href="/en/search"');
    expect(html).toContain('href="/en/login"');
  });
});
