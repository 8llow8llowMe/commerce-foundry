import { describe, expect, it, vi } from 'vitest';
import { gatewayRequest } from './gateway-request';

const ok = (body: unknown) =>
  new Response(
    JSON.stringify({
      dataHeader: { success: true, resultCode: null, resultMessage: null, fieldErrors: null },
      dataBody: body,
    }),
    { status: 200 },
  );

describe('gatewayRequest', () => {
  it('게이트웨이를 직접 부르고 access 토큰을 Authorization 으로 붙인다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(ok({ id: 1 }));

    const result = await gatewayRequest<{ id: number }>('/api/v1/members/me', {
      gatewayUrl: 'http://gw:8000/',
      accessToken: 'access-1',
      fetch: fetchImpl,
    });

    expect(result).toEqual({ id: 1 });
    const [url, init] = fetchImpl.mock.calls[0]!;
    expect(url).toBe('http://gw:8000/api/v1/members/me');
    expect(new Headers(init?.headers).get('authorization')).toBe('Bearer access-1');
  });

  it('토큰이 없으면 Authorization 을 붙이지 않는다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(ok([]));
    await gatewayRequest('/api/v1/products', { gatewayUrl: 'http://gw:8000', fetch: fetchImpl });
    expect(new Headers(fetchImpl.mock.calls[0]![1]?.headers).has('authorization')).toBe(false);
  });

  it('연결 실패는 network ApiError 다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('fetch failed'));
    await expect(
      gatewayRequest('/api/v1/products', { gatewayUrl: 'http://gw:8000', fetch: fetchImpl }),
    ).rejects.toMatchObject({ kind: 'network' });
  });
});
