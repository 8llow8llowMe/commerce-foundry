import { describe, expect, it, vi } from 'vitest';
import type { AuthCookieConfig } from './cookies';
import { createProxyHandler } from './proxy';
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

const unauthorized = () => envelope(401, false, null, 'SECURITY_001');

const request = (path: string, init: RequestInit & { cookie?: string } = {}) => {
  const headers = new Headers(init.headers);
  if (init.cookie) headers.set('cookie', init.cookie);
  return new Request(`http://localhost:3000${path}`, { ...init, headers });
};

const urlOf = (input: RequestInfo | URL) => (typeof input === 'string' ? input : input.toString());
const authOf = (init?: RequestInit) => new Headers(init?.headers).get('authorization');

describe('createProxyHandler', () => {
  it('/api/v1 밖 경로는 게이트웨이로 보내지 않고 BFF_001 404 다', async () => {
    const fetchImpl = vi.fn<typeof fetch>();
    const handler = createProxyHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

    const res = await handler(request('/api/internal/x'));

    expect(res.status).toBe(404);
    expect(await res.json()).toMatchObject({
      dataHeader: { success: false, resultCode: 'BFF_001' },
    });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('경로·쿼리·메서드·본문을 유지하고, 허용 헤더만 넘기고, 쿠키를 Authorization 으로 바꾼다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(envelope(200, true, { ok: 1 }));
    const handler = createProxyHandler({
      gatewayUrl: `${GATEWAY}/`,
      cookies: COOKIES,
      fetch: fetchImpl,
    });

    const res = await handler(
      request('/api/v1/orders?page=0', {
        method: 'POST',
        body: '{"qty":1}',
        cookie: 'cf_at=access-1; cf_rt=refresh-1; other=x',
        headers: {
          'content-type': 'application/json',
          'idempotency-key': 'key-1',
          'accept-language': 'ko-KR',
          authorization: 'Bearer forged',
          'x-forwarded-for': '1.2.3.4',
        },
      }),
    );

    expect(res.status).toBe(200);
    const [url, init] = fetchImpl.mock.calls[0]!;
    expect(urlOf(url)).toBe(`${GATEWAY}/api/v1/orders?page=0`);
    expect(init?.method).toBe('POST');
    expect(new TextDecoder().decode(init?.body as ArrayBuffer)).toBe('{"qty":1}');
    const headers = new Headers(init?.headers);
    expect(headers.get('authorization')).toBe('Bearer access-1');
    expect(headers.get('content-type')).toBe('application/json');
    expect(headers.get('idempotency-key')).toBe('key-1');
    expect(headers.get('accept-language')).toBe('ko-KR');
    expect(headers.has('cookie')).toBe(false);
    expect(headers.has('x-forwarded-for')).toBe(false);
  });

  it('access 쿠키가 없으면 Authorization 을 붙이지 않는다 (클라이언트가 보낸 값도 버린다)', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(envelope(200, true, []));
    const handler = createProxyHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

    await handler(request('/api/v1/products', { headers: { authorization: 'Bearer forged' } }));

    expect(authOf(fetchImpl.mock.calls[0]![1])).toBeNull();
  });

  it('게이트웨이 응답의 Set-Cookie 는 버리고 상태·본문은 그대로 중계한다', async () => {
    const upstream = envelope(404, false, null, 'ORDER_001');
    upstream.headers.append('set-cookie', 'leak=1');
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(upstream);
    const handler = createProxyHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

    const res = await handler(request('/api/v1/orders/1'));

    expect(res.status).toBe(404);
    expect(res.headers.getSetCookie()).toEqual([]);
    expect(await res.json()).toMatchObject({ dataHeader: { resultCode: 'ORDER_001' } });
  });

  it('401 이면 재발급 후 새 토큰으로 한 번 재시도하고 새 쿠키를 심는다', async () => {
    const fetchImpl = vi.fn<typeof fetch>(async (input, init) => {
      if (urlOf(input).endsWith(REISSUE_PATH)) {
        expect(JSON.parse(init?.body as string)).toEqual({ refreshToken: 'refresh-1' });
        return envelope(200, true, { accessToken: 'access-2', refreshToken: 'refresh-2' });
      }
      return authOf(init) === 'Bearer access-2' ? envelope(200, true, { id: 1 }) : unauthorized();
    });
    const handler = createProxyHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

    const res = await handler(
      request('/api/v1/members/me', { cookie: 'cf_at=access-1; cf_rt=refresh-1' }),
    );

    expect(res.status).toBe(200);
    expect(fetchImpl).toHaveBeenCalledTimes(3);
    expect(res.headers.getSetCookie()).toEqual([
      'cf_at=access-2; Path=/; HttpOnly; SameSite=Lax; Max-Age=1800',
      'cf_rt=refresh-2; Path=/; HttpOnly; SameSite=Lax; Max-Age=1209600',
    ]);
  });

  it('재발급이 실패하면 인증 쿠키를 지우고 401 을 그대로 돌려준다', async () => {
    const fetchImpl = vi.fn<typeof fetch>(async (input) =>
      urlOf(input).endsWith(REISSUE_PATH)
        ? envelope(401, false, null, 'SECURITY_002')
        : unauthorized(),
    );
    const handler = createProxyHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

    const res = await handler(request('/api/v1/members/me', { cookie: 'cf_at=a; cf_rt=r' }));

    expect(res.status).toBe(401);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    expect(res.headers.getSetCookie()).toEqual([
      'cf_at=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0',
      'cf_rt=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0',
    ]);
  });

  it('refresh 쿠키가 없으면 재발급하지 않고 401 을 중계한다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(unauthorized());
    const handler = createProxyHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

    const res = await handler(request('/api/v1/members/me', { cookie: 'cf_at=a' }));

    expect(res.status).toBe(401);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it('같은 refresh 토큰의 동시 401 은 재발급을 한 번만 보낸다', async () => {
    let reissueCalls = 0;
    const fetchImpl = vi.fn<typeof fetch>(async (input, init) => {
      if (urlOf(input).endsWith(REISSUE_PATH)) {
        reissueCalls += 1;
        await new Promise((resolve) => setTimeout(resolve, 10));
        return envelope(200, true, { accessToken: 'access-9', refreshToken: 'refresh-9' });
      }
      return authOf(init) === 'Bearer access-9' ? envelope(200, true, {}) : unauthorized();
    });
    const handler = createProxyHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });
    const cookie = 'cf_at=old; cf_rt=refresh-shared';

    const [a, b] = await Promise.all([
      handler(request('/api/v1/a', { cookie })),
      handler(request('/api/v1/b', { cookie })),
    ]);

    expect([a.status, b.status]).toEqual([200, 200]);
    expect(reissueCalls).toBe(1);
  });

  it('게이트웨이 연결 실패는 BFF_002 503 이다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('fetch failed'));
    const handler = createProxyHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

    const res = await handler(request('/api/v1/products'));

    expect(res.status).toBe(503);
    expect(await res.json()).toMatchObject({ dataHeader: { resultCode: 'BFF_002' } });
  });

  it('재발급 성공 뒤 재시도가 연결 실패해도 새 쿠키는 심는다 (회전된 refresh 를 잃지 않는다)', async () => {
    const fetchImpl = vi.fn<typeof fetch>(async (input, init) => {
      if (urlOf(input).endsWith(REISSUE_PATH)) {
        return envelope(200, true, { accessToken: 'access-3', refreshToken: 'refresh-3' });
      }
      if (authOf(init) === 'Bearer access-3') throw new TypeError('fetch failed');
      return unauthorized();
    });
    const handler = createProxyHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

    const res = await handler(
      request('/api/v1/members/me', { cookie: 'cf_at=a; cf_rt=refresh-x' }),
    );

    expect(res.status).toBe(503);
    expect(res.headers.getSetCookie()).toEqual([
      'cf_at=access-3; Path=/; HttpOnly; SameSite=Lax; Max-Age=1800',
      'cf_rt=refresh-3; Path=/; HttpOnly; SameSite=Lax; Max-Age=1209600',
    ]);
  });
});
