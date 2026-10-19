import { beforeEach, describe, expect, it, vi } from 'vitest';
import { serverFetch } from './server-api';

const gatewayRequest = vi.hoisted(() => vi.fn());
vi.mock('@cf/api-client/server', () => ({ gatewayRequest }));
vi.mock('next/headers', () => ({ cookies: async () => ({ get: () => undefined }) }));
vi.mock('next/root-params', () => ({ locale: async () => 'en' }));

beforeEach(() => {
  gatewayRequest.mockReset();
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
});
