import { describe, expect, it, vi } from 'vitest';
import { apiFetch } from './api-fetch';

const ok = (body: unknown) =>
  new Response(
    JSON.stringify({
      dataHeader: { success: true, resultCode: null, resultMessage: null, fieldErrors: null },
      dataBody: body,
    }),
    { status: 200, headers: { 'content-type': 'application/json' } },
  );

describe('apiFetch', () => {
  it('같은 origin 으로 JSON 요청을 보내고 dataBody 를 돌려준다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(ok({ id: 7 }));

    const result = await apiFetch<{ id: number }>(
      '/api/v1/orders',
      { method: 'POST', json: { qty: 1 }, idempotencyKey: 'key-1' },
      fetchImpl,
    );

    expect(result).toEqual({ id: 7 });
    const [path, init] = fetchImpl.mock.calls[0]!;
    expect(path).toBe('/api/v1/orders');
    expect(init?.method).toBe('POST');
    expect(init?.credentials).toBe('same-origin');
    expect(init?.body).toBe('{"qty":1}');
    const headers = new Headers(init?.headers);
    expect(headers.get('content-type')).toBe('application/json');
    expect(headers.get('accept')).toBe('application/json');
    expect(headers.get('idempotency-key')).toBe('key-1');
  });

  it('본문이 없으면 Content-Type 을 붙이지 않는다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(ok([]));
    await apiFetch('/api/v1/products', {}, fetchImpl);
    const headers = new Headers(fetchImpl.mock.calls[0]![1]?.headers);
    expect(headers.has('content-type')).toBe(false);
    expect(fetchImpl.mock.calls[0]![1]?.body).toBeUndefined();
  });

  it('fetch 자체가 실패하면 network ApiError 다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('Failed to fetch'));
    await expect(apiFetch('/api/v1/products', {}, fetchImpl)).rejects.toMatchObject({
      name: 'ApiError',
      kind: 'network',
    });
  });

  it('취소(AbortError)는 ApiError 로 바꾸지 않고 그대로 던진다', async () => {
    const abort = new DOMException('The operation was aborted.', 'AbortError');
    const fetchImpl = vi.fn<typeof fetch>().mockRejectedValue(abort);
    await expect(apiFetch('/api/v1/products', {}, fetchImpl)).rejects.toBe(abort);
  });

  it('사용자 지정 reason 으로 취소하면 그 reason 을 그대로 던진다', async () => {
    const controller = new AbortController();
    const reason = new Error('route change');
    const fetchImpl = vi.fn<typeof fetch>(async () => {
      controller.abort(reason);
      throw reason;
    });
    await expect(
      apiFetch('/api/v1/products', { signal: controller.signal }, fetchImpl),
    ).rejects.toBe(reason);
  });

  it('본문을 읽는 중에 취소돼도 invalidResponse 가 아니라 취소 reason 을 던진다', async () => {
    const controller = new AbortController();
    const reason = new DOMException('The operation was aborted.', 'AbortError');
    const response = new Response('{}', { status: 200 });
    vi.spyOn(response, 'json').mockImplementation(async () => {
      controller.abort(reason);
      throw reason;
    });
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(response);

    await expect(
      apiFetch('/api/v1/products', { signal: controller.signal }, fetchImpl),
    ).rejects.toBe(reason);
  });
});
