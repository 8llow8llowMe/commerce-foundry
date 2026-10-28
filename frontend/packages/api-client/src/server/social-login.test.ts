import { describe, expect, it, vi } from 'vitest';
import type { AuthCookieConfig } from './cookies';
import { createSocialLoginHandler, socialLoginPath } from './social-login';

const GATEWAY = 'http://gateway.test:8000';
const COOKIES: AuthCookieConfig = {
  accessName: 'cf_at',
  refreshName: 'cf_rt',
  accessMaxAgeSec: 1800,
  refreshMaxAgeSec: 1209600,
  secure: false,
};

const envelope = (
  status: number,
  success: boolean,
  body: unknown,
  code: string | null = null,
  headers: Record<string, string> = {},
) =>
  new Response(
    JSON.stringify({
      dataHeader: { success, resultCode: code, resultMessage: null, fieldErrors: null },
      dataBody: body,
    }),
    { status, headers: { 'content-type': 'application/json', ...headers } },
  );

const TOKENS = {
  accessToken: 'access-1',
  accessTokenExpiresIn: 900,
  refreshToken: 'refresh-1',
  refreshTokenExpiresIn: 3600,
};

const BODY = { code: 'code-1', state: 's'.repeat(43), verifier: 'v'.repeat(43) };

const login = (
  provider: string,
  headers: Record<string, string> = { 'sec-fetch-site': 'same-origin' },
) =>
  new Request(`http://localhost:3000/api/v1/auth/oauth/${provider}/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'accept-language': 'en', ...headers },
    body: JSON.stringify(BODY),
  });

const ctx = (provider: string) => ({ params: Promise.resolve({ provider }) });

const handlerWith = (fetchImpl: typeof fetch) =>
  createSocialLoginHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

describe('createSocialLoginHandler', () => {
  it('성공하면 토큰을 httpOnly 쿠키로 심고 본문에서는 토큰을 뺀다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(envelope(200, true, TOKENS));
    const res = await handlerWith(fetchImpl)(login('kakao'), ctx('kakao'));

    expect(res.status).toBe(200);
    expect(res.headers.get('cache-control')).toBe('no-store');
    const text = await res.text();
    expect(JSON.parse(text).dataBody).toBeNull();
    expect(text).not.toContain('access-1');
    expect(text).not.toContain('refresh-1');
    expect(res.headers.getSetCookie()).toEqual([
      'cf_at=access-1; Path=/; HttpOnly; SameSite=Lax; Max-Age=900',
      'cf_rt=refresh-1; Path=/; HttpOnly; SameSite=Lax; Max-Age=3600',
    ]);
  });

  it('게이트웨이의 그 제공자 경로로 본문 · 언어만 넘기고 쿠키는 넘기지 않는다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(envelope(200, true, TOKENS));
    await handlerWith(fetchImpl)(
      login('naver', { 'sec-fetch-site': 'same-origin', cookie: 'cf_at=old' }),
      ctx('naver'),
    );

    const [url, init] = fetchImpl.mock.calls[0]!;
    expect(url).toBe(`${GATEWAY}/api/v1/auth/oauth/naver/login`);
    const headers = new Headers(init?.headers);
    expect(headers.get('accept-language')).toBe('en');
    expect(headers.get('cookie')).toBeNull();
    expect(headers.get('authorization')).toBeNull();
    expect(JSON.parse(new TextDecoder().decode(init?.body as ArrayBuffer))).toEqual(BODY);
  });

  it.each(['google', 'KAKAO', '..', ''])(
    '모르는 제공자 %j 는 게이트웨이를 부르지 않고 BFF_001 404 다',
    async (provider) => {
      const fetchImpl = vi.fn<typeof fetch>();
      const res = await handlerWith(fetchImpl)(login('x'), ctx(provider));
      expect(res.status).toBe(404);
      expect((await res.json()).dataHeader.resultCode).toBe('BFF_001');
      expect(fetchImpl).not.toHaveBeenCalled();
    },
  );

  it('다른 사이트에서 온 요청은 게이트웨이를 부르지 않고 BFF_003 403 이다(login CSRF)', async () => {
    const fetchImpl = vi.fn<typeof fetch>();
    const res = await handlerWith(fetchImpl)(
      login('kakao', { 'sec-fetch-site': 'cross-site' }),
      ctx('kakao'),
    );
    expect(res.status).toBe(403);
    expect((await res.json()).dataHeader.resultCode).toBe('BFF_003');
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it.each([
    [400, 'AUTH_056'],
    [400, 'AUTH_052'],
    [409, 'AUTH_057'],
    [503, 'AUTH_054'],
  ])('실패 %i %s 는 봉투와 상태를 그대로 중계하고 쿠키를 심지 않는다', async (status, code) => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(envelope(status, false, null, code));
    const res = await handlerWith(fetchImpl)(login('kakao'), ctx('kakao'));
    expect(res.status).toBe(status);
    expect((await res.json()).dataHeader.resultCode).toBe(code);
    expect(res.headers.getSetCookie()).toEqual([]);
  });

  it('429 는 Retry-After 까지 중계한다', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(envelope(429, false, null, 'GATEWAY_006', { 'retry-after': '7' }));
    const res = await handlerWith(fetchImpl)(login('kakao'), ctx('kakao'));
    expect(res.status).toBe(429);
    expect(res.headers.get('retry-after')).toBe('7');
  });

  it('연결 실패 · 성공인데 토큰 없음은 BFF_002 503 이다', async () => {
    const down = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('fetch failed'));
    expect((await handlerWith(down)(login('kakao'), ctx('kakao'))).status).toBe(503);

    const empty = vi.fn<typeof fetch>().mockResolvedValue(envelope(200, true, {}));
    const res = await handlerWith(empty)(login('kakao'), ctx('kakao'));
    expect(res.status).toBe(503);
    expect(res.headers.getSetCookie()).toEqual([]);
  });
});

describe('socialLoginPath', () => {
  it('허용 목록 제공자의 게이트웨이 경로', () => {
    expect(socialLoginPath('kakao')).toBe('/api/v1/auth/oauth/kakao/login');
  });
});
