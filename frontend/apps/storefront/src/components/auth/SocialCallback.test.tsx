import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ko } from '@/lib/i18n/dictionaries/ko';
import { SocialCallback, missingAgreementErrors, restartHref } from './SocialCallback';

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }) }));

const t = ko.auth;

describe('SocialCallback', () => {
  it('서버에서는 쿼리를 읽지 않고 처리 중 안내만 그린다', () => {
    const html = renderToStaticMarkup(<SocialCallback provider="kakao" />);
    expect(html).toContain('role="status"');
    expect(html).toContain(t.social.working);
  });

  it('다시 시작은 돌아갈 경로를 이은 로그인 화면이다', () => {
    expect(restartHref('/')).toBe('/login');
    expect(restartHref('/shop?page=2')).toBe('/login?next=%2Fshop%3Fpage%3D2');
  });

  it('필수 동의가 빠진 칸에만 가입 폼과 같은 문구를 붙인다', () => {
    expect(
      missingAgreementErrors({ termsOfService: false, privacyPolicy: true, marketing: false }, t),
    ).toEqual({
      agreements: [],
      termsOfService: [t.errors.AUTH_111],
      privacyPolicy: [],
      marketing: [],
    });
  });
});
