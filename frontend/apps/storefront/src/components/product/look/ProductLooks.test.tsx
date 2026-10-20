import { ApiError } from '@cf/api-client';
import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ProductDetail, ProductLook } from '@/lib/catalog/types';
import { ProductLooks } from './ProductLooks';

// LookPicker 의 담기(useAddToCart)가 라우터를 쓴다
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }) }));

const serverFetch = vi.hoisted(() => vi.fn());
vi.mock('@/lib/server-api', () => ({ serverFetch }));

beforeEach(() => {
  serverFetch.mockReset();
});

const krw = (amount: number) => ({ amount, currency: 'KRW' as const });
const onSale = { code: 'ON_SALE' as const, name: '판매중', description: '' };

const detail = (productId: string): ProductDetail => ({
  productId,
  name: `구성품 ${productId}`,
  summary: null,
  categoryPath: [],
  thumbnail: null,
  images: [],
  descriptionHtml: null,
  salePrice: krw(89000),
  listPrice: null,
  discountRate: null,
  saleStatus: onSale,
  maxQuantityPerOrder: null,
  optionGroups: [],
  skus: [],
  customOrder: null,
});

const look = (lookId: string, productIds: string[]): ProductLook => ({
  lookId,
  title: `룩 ${lookId}`,
  description: null,
  image: null,
  items: productIds.map((productId) => ({
    productId,
    name: `구성품 ${productId}`,
    thumbnail: null,
    salePrice: krw(89000),
    saleStatus: onSale,
  })),
});

/** 룩 응답과 상품 상세를 경로로 나눠 돌려준다. 상세는 known 에 있는 것만 */
const gateway = (looks: ProductLook[], known: string[]) =>
  serverFetch.mockImplementation(async (path: string) => {
    if (path.endsWith('/looks')) return { looks };
    const id = path.split('/').at(-1)!;
    if (known.includes(id)) return detail(id);
    throw new ApiError('notFound', 404, 'PRODUCT_001', null);
  });

const render = async (productId = '1') => {
  const element = await ProductLooks({ productId });
  return element ? renderToStaticMarkup(element).replaceAll('<!-- -->', '') : null;
};

describe('ProductLooks', () => {
  it('이 상품의 룩을 받아 구성품을 상세 API 로 채운다 — 같은 상품은 한 번만 부른다', async () => {
    gateway([look('a', ['1', '2']), look('b', ['1', '3'])], ['1', '2', '3']);
    const html = await render();
    expect(serverFetch).toHaveBeenCalledWith('/api/v1/products/1/looks');
    expect(serverFetch.mock.calls.filter(([path]) => path === '/api/v1/products/1')).toHaveLength(
      1,
    );
    expect(html).toContain('Shop the Look');
    expect(html).toContain('룩 a');
    expect(html).toContain('룩 b');
    expect(html).toContain('구성품 3');
  });

  it('못 받은 구성품은 빼고, 구성품이 모두 빠진 룩은 그리지 않는다', async () => {
    gateway([look('a', ['1', '9']), look('b', ['8'])], ['1']);
    const html = await render();
    expect(html).toContain('룩 a');
    expect(html).not.toContain('구성품 9');
    expect(html).not.toContain('룩 b');
  });

  it('룩 API 가 없거나(404) 실패하거나 룩이 없으면 영역을 그리지 않는다', async () => {
    serverFetch.mockRejectedValue(new ApiError('notFound', 404, 'COMMON_404', null));
    expect(await render()).toBeNull();
    serverFetch.mockRejectedValue(new ApiError('unavailable', 503, 'GATEWAY_002', null));
    expect(await render()).toBeNull();
    gateway([], []);
    expect(await render()).toBeNull();
  });

  it('룩은 4개까지만 보인다', async () => {
    gateway(
      ['a', 'b', 'c', 'd', 'e'].map((id) => look(id, ['1'])),
      ['1'],
    );
    const html = (await render()) ?? '';
    expect(html).toContain('룩 d');
    expect(html).not.toContain('룩 e');
  });
});
