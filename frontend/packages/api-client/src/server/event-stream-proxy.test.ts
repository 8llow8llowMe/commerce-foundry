import { describe, expect, it, vi } from 'vitest';
import type { AuthCookieConfig } from './cookies';
import { createEventStreamProxyHandler } from './event-stream-proxy';

const GATEWAY = 'http://gateway.test:8000';
const PATH = '/api/v1/admin/notifications/stream';
const COOKIES: AuthCookieConfig = {
  accessName: 'cf_admin_at',
  refreshName: null,
  accessMaxAgeSec: 1800,
  secure: false,
};

const request = (init: RequestInit & { cookie?: string } = {}) => {
  const headers = new Headers(init.headers);
  if (init.cookie) headers.set('cookie', init.cookie);
  return new Request(`http://localhost:3001${PATH}?x=1`, { ...init, headers });
};

const handlerWith = (fetchImpl: typeof fetch) =>
  createEventStreamProxyHandler({
    gatewayUrl: GATEWAY,
    cookies: COOKIES,
    fetch: fetchImpl,
    clientIpHeader: null,
    path: PATH,
  });

describe('createEventStreamProxyHandler', () => {
  it('쿠키를 Bearer 로 바꿔 정해 둔 경로로만 보내고, 본문을 모으지 않게 하는 헤더로 흘린다', async () => {
    const body = new ReadableStream<Uint8Array>({
      start: (controller) => controller.enqueue(new TextEncoder().encode('data: 1\n\n')),
    });
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(body, {
        headers: { 'content-type': 'text/event-stream', 'set-cookie': 'x=1' },
      }),
    );
    const incoming = request({
      cookie: 'cf_admin_at=tok; other=1',
      headers: { authorization: 'Bearer evil', 'x-forwarded-for': '1.2.3.4' },
    });

    const res = await handlerWith(fetchImpl)(incoming);

    const [url, init] = fetchImpl.mock.calls[0]!;
    expect(url).toBe(`${GATEWAY}${PATH}`);
    const sent = new Headers(init!.headers);
    expect(sent.get('authorization')).toBe('Bearer tok');
    expect(sent.get('accept')).toBe('text/event-stream');
    expect(sent.get('x-forwarded-for')).toBeNull();
    expect(sent.get('cookie')).toBeNull();
    expect(init!.signal).toBe(incoming.signal);

    expect(res.status).toBe(200);
    // 헤더를 바로 내보내도록 주석 한 줄을 먼저, 그 뒤로 게이트웨이 본문을 그대로
    const reader = res.body!.pipeThrough(new TextDecoderStream()).getReader();
    expect((await reader.read()).value).toBe(': connected\n\n');
    expect((await reader.read()).value).toBe('data: 1\n\n');
    await reader.cancel();
    expect(res.headers.get('content-type')).toBe('text/event-stream; charset=utf-8');
    expect(res.headers.get('cache-control')).toBe('no-cache, no-transform');
    expect(res.headers.get('x-accel-buffering')).toBe('no');
    expect(res.headers.get('set-cookie')).toBeNull();
  });

  it('401 · 403 은 봉투 그대로 중계하고 재시도하지 않는다', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        Response.json(
          { dataHeader: { success: false, resultCode: 'SECURITY_001' }, dataBody: null },
          { status: 401 },
        ),
      );

    const res = await handlerWith(fetchImpl)(request());

    expect(res.status).toBe(401);
    expect(await res.json()).toMatchObject({ dataHeader: { resultCode: 'SECURITY_001' } });
    expect(fetchImpl).toHaveBeenCalledOnce();
    expect(new Headers(fetchImpl.mock.calls[0]![1]!.headers).get('authorization')).toBeNull();
  });

  it('연결 실패는 BFF_002 503, GET 이 아니면 BFF_001 404 다', async () => {
    const failing = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('fetch failed'));
    const res = await handlerWith(failing)(request());
    expect(res.status).toBe(503);
    expect(await res.json()).toMatchObject({ dataHeader: { resultCode: 'BFF_002' } });

    const fetchImpl = vi.fn<typeof fetch>();
    const post = await handlerWith(fetchImpl)(request({ method: 'POST' }));
    expect(post.status).toBe(404);
    expect(fetchImpl).not.toHaveBeenCalled();
  });
});
