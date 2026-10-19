import { describe, expect, it, vi } from 'vitest';
import type { AuthCookieConfig } from './cookies';
import { LOGIN_PATH, createLoginHandler } from './login';

const GATEWAY = 'http://gateway.test:8000';
const COOKIES: AuthCookieConfig = {
  accessName: 'cf_at',
  refreshName: 'cf_rt',
  accessMaxAgeSec: 1800,
  refreshMaxAgeSec: 1209600,
  secure: false,
};

const envelope = (status: number, success: boolean, body: unknown, code: string | null = null) =>
  new Response(
    JSON.stringify({
      dataHeader: { success, resultCode: code, resultMessage: null, fieldErrors: null },
      dataBody: body,
    }),
    { status, headers: { 'content-type': 'application/json' } },
  );

const TOKENS = {
  accessToken: 'access-1',
  accessTokenExpiresIn: 900,
  refreshToken: 'refresh-1',
  refreshTokenExpiresIn: 3600,
};

const login = (headers: Record<string, string> = { 'sec-fetch-site': 'same-origin' }) =>
  new Request(`http://localhost:3000${LOGIN_PATH}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'accept-language': 'en', ...headers },
    body: JSON.stringify({ email: 'a@test.com', password: 'pw' }),
  });

const handlerWith = (fetchImpl: typeof fetch) =>
  createLoginHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

describe('createLoginHandler', () => {
  it('성공하면 토큰을 서버가 준 수명의 httpOnly 쿠키로 심고, 본문에서는 토큰을 뺀다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(envelope(200, true, TOKENS));
    const res = await handlerWith(fetchImpl)(login());

    expect(res.status).toBe(200);
    expect(res.headers.get('cache-control')).toBe('no-store');
    const body = await res.json();
    expect(body.dataBody).toBeNull();
    expect(JSON.stringify(body)).not.toContain('access-1');

    const cookies = res.headers.getSetCookie();
    expect(cookies).toEqual([
      'cf_at=access-1; Path=/; HttpOnly; SameSite=Lax; Max-Age=900',
      'cf_rt=refresh-1; Path=/; HttpOnly; SameSite=Lax; Max-Age=3600',
    ]);
  });

  it('게이트웨이에는 로그인 경로 · 본문 · 언어만 넘기고 쿠키는 넘기지 않는다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(envelope(200, true, TOKENS));
    await handlerWith(fetchImpl)(login({ 'sec-fetch-site': 'same-origin', cookie: 'cf_at=old' }));

    const [url, init] = fetchImpl.mock.calls[0]!;
    expect(url).toBe(`${GATEWAY}${LOGIN_PATH}`);
    const headers = new Headers(init?.headers);
    expect(headers.get('accept-language')).toBe('en');
    expect(headers.get('cookie')).toBeNull();
    expect(headers.get('authorization')).toBeNull();
    expect(new TextDecoder().decode(init?.body as ArrayBuffer)).toContain('a@test.com');
  });

  it('수명이 없으면 앱 설정값을 쓴다', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(envelope(200, true, { accessToken: 'a', refreshToken: 'r' }));
    const cookies = (await handlerWith(fetchImpl)(login())).headers.getSetCookie();
    expect(cookies[0]).toContain('Max-Age=1800');
    expect(cookies[1]).toContain('Max-Age=1209600');
  });

  it('로그인 실패(400 AUTH_001)는 상태 · 봉투를 그대로 중계하고 쿠키를 건드리지 않는다', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(envelope(400, false, null, 'AUTH_001'));
    const res = await handlerWith(fetchImpl)(login());

    expect(res.status).toBe(400);
    expect(await res.json()).toMatchObject({ dataHeader: { resultCode: 'AUTH_001' } });
    expect(res.headers.getSetCookie()).toEqual([]);
  });

  it('다른 사이트에서 온 요청은 게이트웨이로 보내지 않는다(login CSRF)', async () => {
    const fetchImpl = vi.fn<typeof fetch>();
    const res = await handlerWith(fetchImpl)(login({ 'sec-fetch-site': 'cross-site' }));

    expect(res.status).toBe(403);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('연결 실패 · 봉투 아님 · 토큰 없는 성공은 BFF_002 503 이고 쿠키를 심지 않는다', async () => {
    for (const fetchImpl of [
      vi.fn<typeof fetch>().mockRejectedValue(new Error('down')),
      vi.fn<typeof fetch>().mockResolvedValue(new Response('<html>', { status: 502 })),
      vi.fn<typeof fetch>().mockResolvedValue(envelope(200, true, { accessToken: 'a' })),
    ]) {
      const res = await handlerWith(fetchImpl)(login());
      expect(res.status).toBe(503);
      expect(await res.json()).toMatchObject({ dataHeader: { resultCode: 'BFF_002' } });
      expect(res.headers.getSetCookie()).toEqual([]);
    }
  });
});
