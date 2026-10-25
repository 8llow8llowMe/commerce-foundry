import { beforeEach, describe, expect, it, vi } from 'vitest';
import { serverFetch } from './server-api';

const gatewayRequest = vi.hoisted(() => vi.fn());
const cookies = vi.hoisted(() => vi.fn());
vi.mock('@cf/api-client/server', () => ({ gatewayRequest }));
vi.mock('next/headers', () => ({ cookies }));
vi.mock('next/root-params', () => ({ locale: async () => 'en' }));

beforeEach(() => {
  gatewayRequest.mockReset();
  cookies.mockReset();
  cookies.mockResolvedValue({ get: () => ({ value: 'access-token' }) });
});

const sentHeaders = () => new Headers(gatewayRequest.mock.calls[0]?.[1]?.init?.headers);

describe('serverFetch', () => {
  it('스토어 언어를 Accept-Language 로 보낸다', async () => {
    await serverFetch('/api/v1/products');
    expect(sentHeaders().get('accept-language')).toBe('en');
  });

  it('호출자가 정한 Accept-Language 는 덮어쓰지 않는다', async () => {
    await serverFetch('/api/v1/products', { headers: { 'accept-language': 'ko' } });
    expect(sentHeaders().get('accept-language')).toBe('ko');
  });

  it('access 쿠키를 토큰으로 넘긴다', async () => {
    await serverFetch('/api/v1/products');
    expect(gatewayRequest.mock.calls[0]?.[1]?.accessToken).toBe('access-token');
  });

  it('anonymous 면 쿠키를 읽지 않고 토큰 없이 보낸다 — 공개 조회의 캐시가 사용자마다 갈리지 않게', async () => {
    await serverFetch('/api/v1/displays/main', { next: { revalidate: 60 } }, { anonymous: true });
    expect(cookies).not.toHaveBeenCalled();
    expect(gatewayRequest.mock.calls[0]?.[1]?.accessToken).toBeNull();
    expect(gatewayRequest.mock.calls[0]?.[1]?.init?.next).toEqual({ revalidate: 60 });
    expect(sentHeaders().get('accept-language')).toBe('en');
  });
});
