import { afterEach, describe, expect, it, vi } from 'vitest';
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

  it('429 는 재발급·재시도 없이 Retry-After 와 함께 그대로 중계하고 쿠키를 건드리지 않는다', async () => {
    const upstream = envelope(429, false, null, 'GATEWAY_006');
    upstream.headers.set('retry-after', '3');
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(upstream);
    const handler = createProxyHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

    const res = await handler(
      request('/api/v1/carts/me/items', {
        method: 'POST',
        body: '{}',
        headers: { 'sec-fetch-site': 'same-origin' },
        cookie: 'cf_at=access-1; cf_rt=refresh-1',
      }),
    );

    expect(res.status).toBe(429);
    expect(res.headers.get('retry-after')).toBe('3');
    expect(res.headers.getSetCookie()).toEqual([]);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(await res.json()).toMatchObject({ dataHeader: { resultCode: 'GATEWAY_006' } });
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

  describe('재발급 실패 분류 — 일시 장애로는 세션을 지우지 않는다', () => {
    it.each([
      ['재발급 5xx', 'r-5xx', () => envelope(503, false, null, 'GATEWAY_002')],
      ['재발급 응답이 봉투가 아님', 'r-html', () => new Response('<html>', { status: 200 })],
      ['재발급 성공인데 토큰이 없음', 'r-empty', () => envelope(200, true, {})],
    ])('%s → 쿠키를 지우지 않고 BFF_002 503', async (_, refresh, make) => {
      const fetchImpl = vi.fn<typeof fetch>(async (input) =>
        urlOf(input).endsWith(REISSUE_PATH) ? make() : unauthorized(),
      );
      const handler = createProxyHandler({
        gatewayUrl: GATEWAY,
        cookies: COOKIES,
        fetch: fetchImpl,
      });

      const res = await handler(
        request('/api/v1/members/me', { cookie: `cf_at=a; cf_rt=${refresh}` }),
      );

      expect(res.status).toBe(503);
      expect(res.headers.getSetCookie()).toEqual([]);
      expect(await res.json()).toMatchObject({ dataHeader: { resultCode: 'BFF_002' } });
    });

    it('재발급 연결 실패 → 쿠키를 지우지 않고 BFF_002 503', async () => {
      const fetchImpl = vi.fn<typeof fetch>(async (input) => {
        if (urlOf(input).endsWith(REISSUE_PATH)) throw new TypeError('fetch failed');
        return unauthorized();
      });
      const handler = createProxyHandler({
        gatewayUrl: GATEWAY,
        cookies: COOKIES,
        fetch: fetchImpl,
      });

      const res = await handler(request('/api/v1/members/me', { cookie: 'cf_at=a; cf_rt=r-net' }));

      expect(res.status).toBe(503);
      expect(res.headers.getSetCookie()).toEqual([]);
    });
  });

  describe('재발급 직후 옛 refresh 로 온 요청', () => {
    afterEach(() => {
      vi.useRealTimers();
    });

    const rotatingGateway = () => {
      let issued = 0;
      const fetchImpl = vi.fn<typeof fetch>(async (input, init) => {
        if (urlOf(input).endsWith(REISSUE_PATH)) {
          issued += 1;
          // 회전형 refresh: 같은 값으로 두 번째 재발급은 거절된다.
          if (issued > 1) return envelope(401, false, null, 'SECURITY_002');
          return envelope(200, true, { accessToken: 'access-new', refreshToken: 'refresh-new' });
        }
        return authOf(init) === 'Bearer access-new' ? envelope(200, true, {}) : unauthorized();
      });
      return { fetchImpl, reissueCount: () => issued };
    };

    it('유예 시간 안이면 방금 받은 토큰 쌍을 다시 써서 로그아웃시키지 않는다', async () => {
      const { fetchImpl, reissueCount } = rotatingGateway();
      const handler = createProxyHandler({
        gatewayUrl: GATEWAY,
        cookies: COOKIES,
        fetch: fetchImpl,
      });
      const cookie = 'cf_at=old; cf_rt=refresh-rotating';

      const first = await handler(request('/api/v1/a', { cookie }));
      const second = await handler(request('/api/v1/b', { cookie }));

      expect([first.status, second.status]).toEqual([200, 200]);
      expect(reissueCount()).toBe(1);
      expect(second.headers.getSetCookie()[0]).toContain('cf_at=access-new');
    });

    it('유예 시간이 지나면 다시 재발급한다', async () => {
      vi.useFakeTimers({ toFake: ['Date'] });
      const { fetchImpl, reissueCount } = rotatingGateway();
      const handler = createProxyHandler({
        gatewayUrl: GATEWAY,
        cookies: COOKIES,
        fetch: fetchImpl,
      });
      const cookie = 'cf_at=old; cf_rt=refresh-expiring';

      await handler(request('/api/v1/a', { cookie }));
      vi.setSystemTime(Date.now() + 31_000);
      const late = await handler(request('/api/v1/b', { cookie }));

      expect(reissueCount()).toBe(2);
      expect(late.status).toBe(401);
    });
  });

  describe('CSRF — 안전하지 않은 메서드는 같은 origin 요청만 받는다', () => {
    it.each(['cross-site', 'same-site', 'none'])(
      'POST + Sec-Fetch-Site: %s → BFF_003 403, 게이트웨이를 부르지 않는다',
      async (site) => {
        const fetchImpl = vi.fn<typeof fetch>();
        const handler = createProxyHandler({
          gatewayUrl: GATEWAY,
          cookies: COOKIES,
          fetch: fetchImpl,
        });

        const res = await handler(
          request('/api/v1/orders', {
            method: 'POST',
            body: '{}',
            cookie: 'cf_at=a',
            headers: { 'sec-fetch-site': site },
          }),
        );

        expect(res.status).toBe(403);
        expect(await res.json()).toMatchObject({ dataHeader: { resultCode: 'BFF_003' } });
        expect(fetchImpl).not.toHaveBeenCalled();
      },
    );

    it('Sec-Fetch-Site 가 없으면 Origin 으로 판단한다', async () => {
      const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(envelope(200, true, {}));
      const handler = createProxyHandler({
        gatewayUrl: GATEWAY,
        cookies: COOKIES,
        fetch: fetchImpl,
      });

      const foreign = await handler(
        request('/api/v1/orders', {
          method: 'DELETE',
          headers: { origin: 'https://evil.test' },
        }),
      );
      const own = await handler(
        request('/api/v1/orders', {
          method: 'DELETE',
          headers: { origin: 'http://localhost:3000' },
        }),
      );

      expect(foreign.status).toBe(403);
      expect(own.status).toBe(200);
    });

    it('같은 origin POST 와 cross-site GET 은 통과한다', async () => {
      const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(envelope(200, true, {}));
      const handler = createProxyHandler({
        gatewayUrl: GATEWAY,
        cookies: COOKIES,
        fetch: fetchImpl,
      });

      const post = await handler(
        request('/api/v1/orders', {
          method: 'POST',
          body: '{}',
          headers: { 'sec-fetch-site': 'same-origin' },
        }),
      );
      const get = await handler(
        request('/api/v1/products', { headers: { 'sec-fetch-site': 'cross-site' } }),
      );

      expect([post.status, get.status]).toEqual([200, 200]);
    });
  });
});
