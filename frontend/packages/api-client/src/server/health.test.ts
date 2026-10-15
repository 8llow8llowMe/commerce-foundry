import { describe, expect, it, vi } from 'vitest';
import { checkGateway, healthResponse } from './health';

describe('checkGateway', () => {
  it('actuator 가 UP 이면 UP', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ status: 'UP' }));
    await expect(checkGateway('http://gw:8000/', fetchImpl)).resolves.toBe('UP');
    expect(fetchImpl.mock.calls[0]![0]).toBe('http://gw:8000/actuator/health');
  });

  it.each([
    ['DOWN 응답', () => Response.json({ status: 'DOWN' }, { status: 503 })],
    ['200 이지만 UP 아님', () => Response.json({ status: 'OUT_OF_SERVICE' })],
    ['JSON 아님', () => new Response('oops', { status: 200 })],
  ])('%s → DOWN', async (_, make) => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(make());
    await expect(checkGateway('http://gw:8000', fetchImpl)).resolves.toBe('DOWN');
  });

  it('연결 실패 → DOWN', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('fetch failed'));
    await expect(checkGateway('http://gw:8000', fetchImpl)).resolves.toBe('DOWN');
  });
});

describe('healthResponse', () => {
  it('게이트웨이 UP 이면 200 { app: UP, gateway: UP }', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(Response.json({ status: 'UP' }));
    const res = await healthResponse('http://gw:8000', fetchImpl);
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ app: 'UP', gateway: 'UP' });
  });

  it('게이트웨이 DOWN 이면 503 { app: UP, gateway: DOWN }', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('fetch failed'));
    const res = await healthResponse('http://gw:8000', fetchImpl);
    expect(res.status).toBe(503);
    expect(await res.json()).toEqual({ app: 'UP', gateway: 'DOWN' });
  });
});
