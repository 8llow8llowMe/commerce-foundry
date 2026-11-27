import { ApiError } from '@cf/api-client';
import type { NavItem } from '@cf/ui';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  loadStoreNavigation,
  navigationFor,
  readStoreNavigation,
  safeInternalPath,
  STORE_NAVIGATION_NOT_FOUND,
  storeNavigationFailure,
} from './store-navigation';

const serverFetch = vi.hoisted(() => vi.fn());
vi.mock('@/lib/server-api', () => ({ serverFetch }));

beforeEach(() => serverFetch.mockReset());

/** 공개 응답 노드 — 서버 계약의 필드만 */
const node = (extra: Record<string, unknown>) => ({
  label: '메뉴',
  description: null,
  type: 'INTERNAL',
  url: '/shop',
  categoryId: null,
  primary: false,
  shortcut: false,
  children: [],
  ...extra,
});

describe('safeInternalPath', () => {
  it.each(['/', '/shop', '/collections/new-in'])('%s 는 그대로', (path) => {
    expect(safeInternalPath(path)).toBe(path);
  });

  it.each([
    '//evil.example.com',
    'https://example.com',
    'javascript:alert(1)',
    '/a/../b',
    '/a%2F',
    '/a?x=1',
    '/a#x',
    '/한글',
    '/a+b',
    '/a:b',
    '',
    null,
  ])('%s 는 null', (path) => {
    expect(safeInternalPath(path)).toBeNull();
  });
});

describe('readStoreNavigation', () => {
  it('유형별로 링크를 만들고 최상위 설명 · 1차 메뉴 · 바로가기 · 3단계 하위를 옮긴다', () => {
    const items = readStoreNavigation({
      items: [
        node({
          label: ' SHOP ',
          description: '전체 상품',
          primary: true,
          shortcut: true,
          children: [
            node({ label: '신상품', url: '/new', description: '하위 설명은 헤더가 쓰지 않는다' }),
            node({
              label: '치마',
              type: 'CATEGORY',
              url: null,
              categoryId: '42',
              children: [node({ label: '긴 치마', url: '/long' })],
            }),
          ],
        }),
        node({ label: '고객 안내', type: 'NONE', url: null, primary: true }),
        node({ label: '매거진', type: 'EXTERNAL', url: 'https://magazine.example.com/a' }),
      ],
    });
    expect(items).toEqual<NavItem[]>([
      {
        label: 'SHOP',
        description: '전체 상품',
        href: '/shop',
        primary: true,
        shortcut: true,
        children: [
          { label: '신상품', href: '/new' },
          {
            label: '치마',
            href: '/shop?category=42',
            children: [{ label: '긴 치마', href: '/long' }],
          },
        ],
      },
      { label: '고객 안내', description: '', href: null, primary: true },
      {
        label: '매거진',
        description: '',
        href: 'https://magazine.example.com/a',
        external: true,
      },
    ]);
  });

  it('틀린 항목(모르는 유형 · 빈 표시명 · 안전하지 않은 주소 · 틀린 카테고리)은 하위째 뺀다', () => {
    const items = readStoreNavigation({
      items: [
        node({ label: 'A', type: 'UNKNOWN' }),
        node({ label: '  ' }),
        node({ label: 'B', url: '//evil.example.com', children: [node({ label: 'B-1' })] }),
        node({ label: 'C', type: 'EXTERNAL', url: 'javascript:alert(1)' }),
        node({ label: 'D', type: 'EXTERNAL', url: 'https://m.example.com/%2e' }),
        node({ label: 'E', type: 'CATEGORY', url: null, categoryId: '0' }),
        node({
          label: 'OK',
          children: [node({ label: 'bad', url: 'http://x.example.com', type: 'EXTERNAL' })],
        }),
        'not-an-object',
      ],
    });
    expect(items).toEqual([{ label: 'OK', description: '', href: '/shop' }]);
  });

  it('4단계 이하는 그리지 않는다', () => {
    const items = readStoreNavigation({
      items: [
        node({
          label: '1',
          children: [
            node({
              label: '2',
              children: [node({ label: '3', children: [node({ label: '4' })] })],
            }),
          ],
        }),
      ],
    });
    expect(items?.[0]?.children?.[0]?.children?.[0]).toEqual({ label: '3', href: '/shop' });
  });

  it('빈 게시본은 빈 메뉴, 모양이 다르면 null', () => {
    expect(readStoreNavigation({ items: [] })).toEqual([]);
    expect(readStoreNavigation(null)).toBeNull();
    expect(readStoreNavigation({ items: 'x' })).toBeNull();
    expect(readStoreNavigation({})).toBeNull();
  });
});

describe('navigationFor', () => {
  const fallback: NavItem[] = [{ label: 'BRAND', description: '기본', href: '/brand' }];

  it('게시본(빈 메뉴 포함)이면 그것, 미게시 · 장애면 브랜드 설정 메뉴', () => {
    expect(navigationFor({ kind: 'published', items: [] }, fallback)).toEqual([]);
    expect(navigationFor({ kind: 'unpublished' }, fallback)).toBe(fallback);
    expect(navigationFor({ kind: 'unavailable' }, fallback)).toBe(fallback);
  });
});

describe('loadStoreNavigation', () => {
  it('요청 언어로 캐시 없이 토큰 없이 부른다', async () => {
    serverFetch.mockResolvedValue({ items: [] });
    await expect(loadStoreNavigation('en')).resolves.toEqual({ kind: 'published', items: [] });
    expect(serverFetch).toHaveBeenCalledWith(
      '/api/v1/store/navigation?locale=en',
      expect.objectContaining({ cache: 'no-store' }),
      { anonymous: true },
    );
  });

  it('미게시 404 는 unpublished, 장애 · 모양 불일치는 unavailable', async () => {
    expect(
      storeNavigationFailure(new ApiError('notFound', 404, STORE_NAVIGATION_NOT_FOUND, null)),
    ).toEqual({ kind: 'unpublished' });
    expect(storeNavigationFailure(new ApiError('notFound', 404, 'OTHER', null))).toEqual({
      kind: 'unavailable',
    });
    serverFetch.mockRejectedValueOnce(new ApiError('unavailable', 503, null, null));
    await expect(loadStoreNavigation('ko')).resolves.toEqual({ kind: 'unavailable' });
    serverFetch.mockResolvedValueOnce({ menu: [] });
    await expect(loadStoreNavigation('ja' as 'ko')).resolves.toEqual({ kind: 'unavailable' });
  });
});
