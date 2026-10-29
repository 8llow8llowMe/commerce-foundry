import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ko } from '@/lib/i18n/dictionaries/ko';
import { SocialAccounts, requestLinkAuthorizeUrl } from './SocialAccounts';

const apiFetch = vi.hoisted(() => vi.fn());
vi.mock('@cf/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@cf/api-client')>()),
  apiFetch,
}));

describe('SocialAccounts', () => {
  it('서버에서는 목록을 읽기 전 불러오는 중 안내만 그린다', () => {
    const html = renderToStaticMarkup(<SocialAccounts linked={null} />);
    expect(html).toContain('role="status"');
    expect(html).toContain(ko.account.loading);
  });

  it('재인증 비밀번호는 연결용 인가 URL 요청 본문에만 싣는다(주소에 넣지 않는다)', async () => {
    apiFetch.mockResolvedValue({});
    await requestLinkAuthorizeUrl('naver', 'pw-secret-1234');
    expect(apiFetch).toHaveBeenCalledWith(
      '/api/v1/members/me/social-accounts/naver/authorize-url',
      {
        method: 'POST',
        json: { currentPassword: 'pw-secret-1234' },
      },
    );
    expect(String(apiFetch.mock.calls[0]?.[0])).not.toContain('pw-secret');
  });
});
