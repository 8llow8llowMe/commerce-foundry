import type { FooterLink, HomeMediaSlot } from '@cf/ui';
import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
import type { StoreFooterState } from '@/lib/store-footer';
import type { StoreProfileState } from '@/lib/store-profile';
import { STORE_PROFILE_FIXTURE } from '@/lib/store-profile-fixtures';
import { SiteFooter } from './SiteFooter';

// [locale] 레이아웃 아래 세그먼트 — null 이면 메인
const segment = vi.hoisted(() => vi.fn<() => string | null>());
vi.mock('next/navigation', () => ({ useSelectedLayoutSegment: segment }));

// 실정보가 아닌 자리 값만 쓴다 (공개 저장소)
const LINKS: FooterLink[] = [
  { label: '약관', href: '/terms' },
  { label: '방침', href: '/privacy', emphasis: true },
];
const FULL: StoreProfileState = { kind: 'registered', profile: STORE_PROFILE_FIXTURE };
const withProfile = (patch: Partial<typeof STORE_PROFILE_FIXTURE>): StoreProfileState => ({
  kind: 'registered',
  profile: { ...STORE_PROFILE_FIXTURE, ...patch },
});

const BACKDROP: HomeMediaSlot = {
  image: { src: '/look.jpg', alt: '마지막 화보' },
  placeholder: '마지막 화보',
};

const render = (
  store: StoreProfileState = FULL,
  links = LINKS,
  labels = ko.footer,
  backdrop: HomeMediaSlot | null = null,
) =>
  renderToStaticMarkup(
    <SiteFooter
      brandName="ACME"
      links={links}
      store={store}
      labels={labels}
      backdrop={backdrop}
      year={2026}
    />,
  );

describe('SiteFooter', () => {
  beforeEach(() => segment.mockReturnValue(null));

  it('법정 정보 5줄과 공정위 확인 링크(새 창)를 보이고, 고객센터는 위 줄에 전화 · 이메일 · 운영 시간으로 둔다', () => {
    const html = render();
    for (const label of ['상호', '대표', '사업자등록번호', '통신판매업 신고번호', '주소']) {
      expect(html).toContain(`<dt>${label}</dt>`);
    }
    expect(html).toContain('<dd>테스트상점</dd>');
    expect(html).not.toContain('<dt>고객센터</dt>');
    expect(html).toContain('<a href="tel:0000000000" class="text-lg">00-0000-0000</a>');
    expect(html).toContain('href="mailto:help@example.com"');
    expect(html).toContain('평일 10:00-17:00');
    expect(html).toMatch(
      /<a href="https:\/\/www.ftc.go.kr\/bizCommPop.do\?wrkr_no=0000000001" target="_blank" rel="noopener noreferrer"/,
    );
  });

  it('선택 칸(통신판매 · 전화 · 운영 시간)이 없으면 그 줄만 뺀다', () => {
    const html = render(
      withProfile({
        mailOrderRegistrationNumber: null,
        customerServicePhone: null,
        customerServiceHours: null,
      }),
    );
    expect(html).not.toContain('<dt>통신판매업 신고번호</dt>');
    expect(html).not.toContain('tel:');
    expect(html).toContain('href="mailto:help@example.com"');
  });

  it('저작권 표시 이름은 등록된 쇼핑몰 이름이고, 워드마크는 브랜드 이름 그대로다', () => {
    const html = render().replace(/<!-- -->/g, '');
    expect(html).toContain('© 2026 테스트몰');
    expect(html).toContain('aria-label="ACME"');
  });

  it('한국이 아닌 국가는 일반 이름으로 번호만 보이고 공정위 링크를 만들지 않는다', () => {
    const html = render(withProfile({ countryCode: 'US' }));
    expect(html).toContain('<dt>사업자 번호</dt>');
    expect(html).toContain('<dd>000-00-00001</dd>');
    expect(html).not.toContain('ftc.go.kr');
  });

  it('미등록이면 사업자 · 고객센터 블록 없이 브랜드 이름으로 그린다', () => {
    const html = render({ kind: 'unregistered' });
    expect(html).not.toContain('aria-label="사업자 정보"');
    expect(html).not.toContain('aria-label="고객센터"');
    expect(html).not.toContain('role="status"');
    expect(html.replace(/<!-- -->/g, '')).toContain('© 2026 ACME');
  });

  it('장애면 지난 값 대신 안내 한 줄만 둔다', () => {
    const html = render({ kind: 'unavailable' });
    expect(html).not.toContain('aria-label="사업자 정보"');
    expect(html).not.toContain('mailto:');
    expect(html).toContain(`<p role="status">${ko.footer.businessUnavailable}</p>`);
  });

  it('고객 안내 링크는 데이터대로, emphasis 는 굵게', () => {
    const html = render();
    expect(html).toMatch(/<a class="hover:underline text-muted-fg [^"]*" href="\/terms">약관<\/a>/);
    expect(html).toMatch(/<a class="hover:underline font-semibold" href="\/privacy">방침<\/a>/);
    expect(render(FULL, [])).not.toContain('aria-label="고객 안내"');
    expect(render({ kind: 'unregistered' }, [])).not.toContain('aria-label="고객센터"');
  });

  it('영문 스토어는 사업자 정보 항목 이름을 영문 사전으로 보인다', () => {
    const html = render(FULL, LINKS, en.footer);
    expect(html).toContain('aria-label="Company information"');
    expect(html).toContain('<dt>Business registration no.</dt>');
    expect(html).not.toContain('사업자등록번호');
  });

  it('워드마크는 브랜드 이름을 대문자로 폭에 맞춰 그리고, 홈 링크의 이름은 원래 이름이다', () => {
    const html = render();
    expect(html).toContain('aria-label="ACME"');
    expect(html).toMatch(/<text[^>]*textLength="1000" lengthAdjust="spacing"[^>]*>ACME<\/text>/);
  });

  it('메인에서만 화보를 밝기 0.62 로 깔고 어두운 바탕으로 바꾼다', () => {
    const home = render(FULL, LINKS, ko.footer, BACKDROP);
    expect(home).toContain('data-backdrop=""');
    expect(home).toMatch(/<img src="\/look.jpg" alt=""[^>]*brightness-\[0.62\]/);

    segment.mockReturnValue('products');
    const detail = render(FULL, LINKS, ko.footer, BACKDROP);
    expect(detail).not.toContain('data-backdrop=""');
    expect(detail).not.toContain('/look.jpg');
  });

  it('화보가 아직 없으면 메인은 사진 없이 어두운 바탕만', () => {
    const html = render(FULL, LINKS, ko.footer, { image: null, placeholder: '촬영 전' });
    expect(html).toContain('data-backdrop=""');
    expect(html).not.toContain('<img');
    expect(html).not.toContain('촬영 전');
  });

  describe('게시 푸터 채널 · 저작권(#600)', () => {
    const PUBLISHED: StoreFooterState = {
      kind: 'published',
      footer: {
        copyrightText: 'Copyright <b>테스트</b>',
        links: [
          { type: 'INSTAGRAM', label: '인스타그램', url: 'https://www.instagram.com/example_shop' },
          { type: 'KAKAO_CHANNEL', label: '카카오 상담', url: 'https://pf.kakao.com/_abc/chat' },
          { type: 'CUSTOM', label: '블로그', url: 'https://blog.example.com' },
        ],
      },
    };
    const renderWith = (channels: StoreFooterState, labels = ko.footer) =>
      renderToStaticMarkup(
        <SiteFooter
          brandName="ACME"
          links={LINKS}
          store={FULL}
          channels={channels}
          labels={labels}
          year={2026}
        />,
      ).replace(/<!-- -->/g, '');

    it('채널은 새 창 안전 속성 · 로케일 표시명 · 새 창 안내로, 아이콘 채널은 이름을 스크린리더로 단다', () => {
      const html = renderWith(PUBLISHED);
      expect(html).toContain('aria-label="채널"');
      expect(html).toMatch(
        /<a href="https:\/\/www.instagram.com\/example_shop" target="_blank" rel="noopener noreferrer" title="인스타그램"[^>]*><svg aria-hidden="true"[^]*?<span class="sr-only">인스타그램<\/span><span class="sr-only"> \(새 창에서 열림\)<\/span><\/a>/,
      );
      expect(html).toMatch(
        /<a href="https:\/\/blog.example.com" target="_blank" rel="noopener noreferrer" class=/,
      );
      expect(html).toContain('<span class="">블로그</span>');
      expect(html.indexOf('인스타그램')).toBeLessThan(html.indexOf('카카오 상담'));
    });

    it('저작권 문구는 게시본을 글자 그대로(이스케이프) 보이고 기본 표기를 대신한다', () => {
      const html = renderWith(PUBLISHED);
      expect(html).toContain('Copyright &lt;b&gt;테스트&lt;/b&gt;');
      expect(html).not.toContain('© 2026 테스트몰');
    });

    it('저작권 문구가 없는 게시본은 기본 표기, 링크가 없으면 채널 줄이 없다', () => {
      const html = renderWith({ kind: 'published', footer: { copyrightText: null, links: [] } });
      expect(html).toContain('© 2026 테스트몰');
      expect(html).not.toContain('aria-label="채널"');
    });

    it('미게시 · 장애면 채널 · 저작권만 숨기고 기존 푸터는 그대로다', () => {
      for (const channels of [{ kind: 'unpublished' }, { kind: 'unavailable' }] as const) {
        const html = renderWith(channels);
        expect(html).not.toContain('aria-label="채널"');
        expect(html).toContain('© 2026 테스트몰');
        expect(html).toContain('aria-label="사업자 정보"');
        expect(html).toContain('href="/terms"');
      }
    });

    it('영문 사전으로 묶음 이름 · 새 창 안내를 바꾼다', () => {
      const html = renderWith(PUBLISHED, en.footer);
      expect(html).toContain('aria-label="Channels"');
      expect(html).toContain('(opens in a new window)');
    });
  });
});
