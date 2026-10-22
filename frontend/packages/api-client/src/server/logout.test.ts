import { describe, expect, it, vi } from 'vitest';
import type { AuthCookieConfig } from './cookies';
import { LOGOUT_PATH, createLogoutHandler } from './logout';
import { REISSUE_PATH } from './reissue';

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
  body: unknown = null,
  code: string | null = null,
) =>
  new Response(
    JSON.stringify({
      dataHeader: { success, resultCode: code, resultMessage: null, fieldErrors: null },
      dataBody: body,
    }),
    { status, headers: { 'content-type': 'application/json' } },
  );

const CLEARED = [
  'cf_at=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0',
  'cf_rt=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0',
];

const logout = (cookie: string | null, site = 'same-origin') => {
  const headers = new Headers({ 'sec-fetch-site': site });
  if (cookie) headers.set('cookie', cookie);
  return new Request(`http://localhost:3000${LOGOUT_PATH}`, { method: 'POST', headers });
};

const urlOf = (input: RequestInfo | URL) => (typeof input === 'string' ? input : input.toString());
const authOf = (init?: RequestInit) => new Headers(init?.headers).get('authorization');
const bodyOf = (init?: RequestInit) => JSON.parse(init?.body as string);

const handlerWith = (fetchImpl: typeof fetch) =>
  createLogoutHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

describe('createLogoutHandler', () => {
  it('access 를 Bearer 로, refresh 를 본문으로 보내고 쿠키를 지운다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(envelope(200, true));
    const res = await handlerWith(fetchImpl)(logout('cf_at=access-1; cf_rt=refresh-1'));

    expect(res.status).toBe(200);
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(await res.json()).toMatchObject({ dataHeader: { success: true }, dataBody: null });
    expect(res.headers.getSetCookie()).toEqual(CLEARED);

    expect(fetchImpl).toHaveBeenCalledTimes(1);
    const [url, init] = fetchImpl.mock.calls[0]!;
    expect(url).toBe(`${GATEWAY}${LOGOUT_PATH}`);
    expect(authOf(init)).toBe('Bearer access-1');
    expect(bodyOf(init)).toEqual({ refreshToken: 'refresh-1' });
  });

  it('401 이면(access 만료) 재발급한 뒤 새 토큰으로 한 번 다시 부른다', async () => {
    const fetchImpl = vi.fn<typeof fetch>(async (input, init) => {
      if (urlOf(input).endsWith(REISSUE_PATH)) {
        return envelope(200, true, { accessToken: 'access-2', refreshToken: 'refresh-out-2' });
      }
      return authOf(init) === 'Bearer access-2'
        ? envelope(200, true)
        : envelope(401, false, null, 'SECURITY_001');
    });
    const res = await handlerWith(fetchImpl)(logout('cf_at=expired; cf_rt=refresh-out-1'));

    expect(fetchImpl).toHaveBeenCalledTimes(3);
    const [, retryInit] = fetchImpl.mock.calls[2]!;
    // 재발급이 회전시킨 새 refresh 로 family 를 지운다
    expect(bodyOf(retryInit)).toEqual({ refreshToken: 'refresh-out-2' });
    expect(res.headers.getSetCookie()).toEqual(CLEARED);
  });

  it('access 쿠키가 없고 refresh 만 있으면 재발급부터 한다', async () => {
    const fetchImpl = vi.fn<typeof fetch>(async (input) =>
      urlOf(input).endsWith(REISSUE_PATH)
        ? envelope(200, true, { accessToken: 'access-3', refreshToken: 'refresh-only-2' })
        : envelope(200, true),
    );
    await handlerWith(fetchImpl)(logout('cf_rt=refresh-only-1'));

    expect(fetchImpl.mock.calls.map(([input]) => urlOf(input))).toEqual([
      `${GATEWAY}${REISSUE_PATH}`,
      `${GATEWAY}${LOGOUT_PATH}`,
    ]);
    expect(authOf(fetchImpl.mock.calls[1]![1])).toBe('Bearer access-3');
  });

  it('재발급 거절 · 서버 장애 · 연결 실패여도 쿠키를 지우고 200 이다', async () => {
    for (const fetchImpl of [
      vi.fn<typeof fetch>(async (input) =>
        urlOf(input).endsWith(REISSUE_PATH)
          ? envelope(401, false, null, 'AUTH_011')
          : envelope(401, false, null, 'SECURITY_001'),
      ),
      vi.fn<typeof fetch>().mockResolvedValue(envelope(503, false, null, 'AUTH_013')),
      vi.fn<typeof fetch>().mockRejectedValue(new Error('down')),
    ]) {
      const res = await handlerWith(fetchImpl)(logout('cf_at=a; cf_rt=refresh-fail'));
      expect(res.status).toBe(200);
      expect(res.headers.getSetCookie()).toEqual(CLEARED);
    }
  });

  it('쿠키가 없으면 게이트웨이를 부르지 않고 쿠키만 지운다', async () => {
    const fetchImpl = vi.fn<typeof fetch>();
    const res = await handlerWith(fetchImpl)(logout(null));

    expect(fetchImpl).not.toHaveBeenCalled();
    expect(res.status).toBe(200);
    expect(res.headers.getSetCookie()).toEqual(CLEARED);
  });

  it('다른 사이트에서 온 요청은 로그아웃시키지 않는다', async () => {
    const fetchImpl = vi.fn<typeof fetch>();
    const res = await handlerWith(fetchImpl)(logout('cf_at=a; cf_rt=r', 'cross-site'));

    expect(res.status).toBe(403);
    expect(fetchImpl).not.toHaveBeenCalled();
    expect(res.headers.getSetCookie()).toEqual([]);
  });

  it('로그아웃 · 재발급 요청 모두 X-Forwarded-For 를 클라이언트 IP 한 값으로 보낸다', async () => {
    const fetchImpl = vi.fn<typeof fetch>(async (input) =>
      urlOf(input).endsWith(REISSUE_PATH)
        ? envelope(200, true, { accessToken: 'access-ip', refreshToken: 'refresh-ip-2' })
        : envelope(200, true),
    );
    const request = new Request(`http://localhost:3000${LOGOUT_PATH}`, {
      method: 'POST',
      headers: {
        'sec-fetch-site': 'same-origin',
        cookie: 'cf_rt=refresh-ip',
        'x-forwarded-for': '1.2.3.4',
        'x-real-ip': '203.0.113.7',
      },
    });

    await handlerWith(fetchImpl)(request);

    expect(fetchImpl).toHaveBeenCalledTimes(2);
    for (const [, init] of fetchImpl.mock.calls) {
      expect(new Headers(init?.headers).get('x-forwarded-for')).toBe('203.0.113.7');
    }
  });

  describe('refresh 쿠키가 없는 설정(어드민)', () => {
    const adminHandlerWith = (fetchImpl: typeof fetch) =>
      createLogoutHandler({
        gatewayUrl: GATEWAY,
        cookies: {
          accessName: 'cf_admin_at',
          refreshName: null,
          accessMaxAgeSec: 1800,
          secure: false,
        },
        fetch: fetchImpl,
      });

    it('access 를 Bearer 로, 본문은 {} 로 보내고 access 쿠키만 지운다', async () => {
      const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(envelope(200, true));
      const res = await adminHandlerWith(fetchImpl)(logout('cf_admin_at=admin-1; cf_rt=refresh-1'));

      expect(fetchImpl).toHaveBeenCalledTimes(1);
      expect(urlOf(fetchImpl.mock.calls[0]![0])).toBe(`${GATEWAY}${LOGOUT_PATH}`);
      expect(authOf(fetchImpl.mock.calls[0]![1])).toBe('Bearer admin-1');
      expect(bodyOf(fetchImpl.mock.calls[0]![1])).toEqual({});
      expect(res.status).toBe(200);
      expect(res.headers.getSetCookie()).toEqual([
        'cf_admin_at=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0',
      ]);
    });

    it('401 이어도 재발급하지 않는다', async () => {
      const fetchImpl = vi
        .fn<typeof fetch>()
        .mockResolvedValue(envelope(401, false, null, 'SECURITY_001'));
      const res = await adminHandlerWith(fetchImpl)(logout('cf_admin_at=expired'));

      expect(fetchImpl).toHaveBeenCalledTimes(1);
      expect(res.status).toBe(200);
    });

    it('access 쿠키도 없으면 게이트웨이를 부르지 않고 쿠키만 지운다', async () => {
      const fetchImpl = vi.fn<typeof fetch>();
      const res = await adminHandlerWith(fetchImpl)(logout(null));

      expect(fetchImpl).not.toHaveBeenCalled();
      expect(res.status).toBe(200);
    });
  });
});
