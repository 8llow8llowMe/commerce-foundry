import { ApiError } from '@cf/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  loadStoreFooter,
  readStoreFooter,
  safeFooterUrl,
  STORE_FOOTER_NOT_FOUND,
  storeFooterFailure,
} from './store-footer';

const serverFetch = vi.hoisted(() => vi.fn());
vi.mock('@/lib/server-api', () => ({ serverFetch }));

beforeEach(() => serverFetch.mockReset());

describe('safeFooterUrl', () => {
  it.each([
    'https://www.instagram.com/example_shop',
    'https://pf.kakao.com/_abc/chat',
    'https://blog.example.com',
  ])('안전한 https 주소 %s 는 그대로', (url) => {
    expect(safeFooterUrl(url)).toBe(url);
  });

  it.each([
    'http://example.com',
    'javascript:alert(1)',
    'data:text/html,x',
    '//example.com',
    '/relative',
    'https://example.com/?q=1',
    'https://example.com/#x',
    'https://user@example.com',
    'https://example.com:444',
    'https://127.0.0.1',
    'https://localhost',
    'https://shop.internal',
    'https://example.com/a b',
    'https://example.com/../x',
    null,
  ])('안전하지 않은 %s 는 null', (url) => {
    expect(safeFooterUrl(url)).toBeNull();
  });
});

describe('readStoreFooter', () => {
  it('틀린 링크만 빼고 나머지는 순서대로, 빈 저작권은 null', () => {
    expect(
      readStoreFooter({
        copyrightText: '  ',
        links: [
          { type: 'INSTAGRAM', label: ' 인스타 ', url: 'https://instagram.com/a' },
          { type: 'UNKNOWN', label: 'x', url: 'https://example.com' },
          { type: 'CUSTOM', label: '', url: 'https://example.com' },
          { type: 'CUSTOM', label: '나쁨', url: 'javascript:alert(1)' },
          { type: 'CUSTOM', label: '블로그', url: 'https://blog.example.com' },
        ],
      }),
    ).toEqual({
      copyrightText: null,
      links: [
        { type: 'INSTAGRAM', label: '인스타', url: 'https://instagram.com/a' },
        { type: 'CUSTOM', label: '블로그', url: 'https://blog.example.com' },
      ],
    });
  });

  it('모양이 다르면 null', () => {
    expect(readStoreFooter(null)).toBeNull();
    expect(readStoreFooter({ copyrightText: 'x' })).toBeNull();
    expect(readStoreFooter({ copyrightText: 1, links: [] })).toBeNull();
  });
});

describe('loadStoreFooter', () => {
  it('요청 언어로 캐시 없이 토큰 없이 부른다', async () => {
    serverFetch.mockResolvedValue({ copyrightText: 'c', links: [] });
    await expect(loadStoreFooter('en')).resolves.toEqual({
      kind: 'published',
      footer: { copyrightText: 'c', links: [] },
    });
    expect(serverFetch).toHaveBeenCalledWith(
      '/api/v1/store/footer?locale=en',
      expect.objectContaining({ cache: 'no-store' }),
      { anonymous: true },
    );
  });

  it('미게시 404 는 unpublished, 장애는 unavailable', async () => {
    expect(storeFooterFailure(new ApiError('notFound', 404, STORE_FOOTER_NOT_FOUND, null))).toEqual(
      { kind: 'unpublished' },
    );
    expect(storeFooterFailure(new ApiError('notFound', 404, 'OTHER', null))).toEqual({
      kind: 'unavailable',
    });
    serverFetch.mockRejectedValueOnce(new ApiError('unavailable', 503, null, null));
    await expect(loadStoreFooter('ko')).resolves.toEqual({ kind: 'unavailable' });
  });
});
