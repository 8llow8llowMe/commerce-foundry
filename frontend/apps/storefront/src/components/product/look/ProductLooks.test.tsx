import { ApiError } from '@cf/api-client';
import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ProductDetail, ProductListItem, ProductLook, SaleStatus } from '@/lib/catalog/types';
import { ProductLooks } from './ProductLooks';

// LookPicker 의 담기(useAddToCart)가 라우터를 쓴다
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }) }));

const serverFetch = vi.hoisted(() => vi.fn());
vi.mock('@/lib/server-api', () => ({ serverFetch }));

beforeEach(() => {
  serverFetch.mockReset();
});

const krw = (amount: number) => ({ amount, currency: 'KRW' as const });
const onSale: SaleStatus = { code: 'ON_SALE', name: '판매중', description: '' };
const stopped: SaleStatus = { code: 'STOP_SELLING', name: '판매 종료', description: '' };

const detail = (productId: string, saleStatus: SaleStatus = onSale): ProductDetail => ({
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
  saleStatus,
  maxQuantityPerOrder: null,
  optionGroups: [],
  skus: [],
  customOrder: null,
  preorder: null,
});

/** 상품 목록 항목과 같은 구성품 카드(BE #62) */
const card = (productId: string): ProductListItem => ({
  productId,
  name: `구성품 ${productId}`,
  thumbnail: null,
  hoverImage: null,
  salePrice: krw(89000),
  listPrice: null,
  discountRate: null,
  hasOptionSurcharge: false,
  saleStatus: onSale,
  customOrder: false,
  preorder: false,
});

const look = (lookId: string, productIds: string[]): ProductLook => ({
  lookId,
  title: `룩 ${lookId}`,
  image: {
    url: `https://cdn.example.com/looks/${lookId}.jpg`,
    width: 1200,
    height: 1600,
    altText: null,
  },
  products: productIds.map(card),
});

/** 룩 응답과 상품 상세를 경로로 나눠 돌려준다. 상세는 known 에 있는 것만 */
const gateway = (looks: unknown, known: string[], stopSelling: string[] = []) =>
  serverFetch.mockImplementation(async (path: string) => {
    if (path.endsWith('/looks')) return { looks };
    const id = path.split('/').at(-1)!;
    if (known.includes(id)) return detail(id, stopSelling.includes(id) ? stopped : onSale);
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

  it('못 받은 구성품은 빼고, 구성품을 모두 못 받은 룩은 화보만 남기고 함께 담기를 숨긴다', async () => {
    gateway([look('a', ['1', '9']), look('b', ['8'])], ['1']);
    const html = (await render()) ?? '';
    expect(html).toContain('룩 a');
    expect(html).not.toContain('구성품 9');
    expect(html).toContain('룩 b');
    expect(html).toContain('looks/b.jpg');
    expect(html.match(/함께 담기/g)).toHaveLength(1);
  });

  it('구성품이 모두 빠진 룩(products: [])은 남기고 함께 담기만 숨긴다', async () => {
    gateway([look('a', [])], []);
    const html = (await render()) ?? '';
    expect(html).toContain('룩 a');
    expect(html).not.toContain('함께 담기');
    expect(serverFetch).toHaveBeenCalledTimes(1);
  });

  it('구성품 순서를 따르고, 판매 종료 구성품은 상태와 함께 보이되 고를 수 없다', async () => {
    gateway([look('a', ['3', '2'])], ['2', '3'], ['2']);
    const html = (await render()) ?? '';
    expect(html.indexOf('구성품 3')).toBeLessThan(html.indexOf('구성품 2'));
    expect(html).toContain('판매 종료');
    expect(html.match(/<input[^>]*disabled=""/g)).toHaveLength(1);
  });

  it('제목 · 설명 · 대체 텍스트는 텍스트로만 그리고 설명의 개행은 white-space 로 살린다', async () => {
    gateway(
      [
        {
          ...look('a', ['1']),
          title: '<b>가을</b> 룩',
          description: '첫 줄\n<script>alert(1)</script>',
          image: {
            url: 'https://cdn.example.com/a.jpg',
            width: null,
            height: null,
            altText: '"><img>',
          },
        },
      ],
      ['1'],
    );
    const html = (await render()) ?? '';
    expect(html).toContain('&lt;b&gt;가을&lt;/b&gt; 룩');
    expect(html).not.toContain('<script>');
    expect(html).toContain('whitespace-pre-line');
    expect(html).toContain('첫 줄\n&lt;script&gt;');
    expect(html).toContain('alt="&quot;&gt;&lt;img&gt;"');
  });

  it('응답 모양이 어긋나도 쓸 수 있는 룩만 그리고 페이지를 깨뜨리지 않는다', async () => {
    // 이전 초안 모양(items) · 아이디 없는 룩 · 문자열 — 구성품이 없으니 함께 담기는 숨긴다
    gateway(
      [
        { lookId: 'old', title: '초안 룩', image: null, items: [card('1')] },
        { title: '아이디 없음' },
        'x',
        look('a', ['1']),
      ],
      ['1'],
    );
    const html = (await render()) ?? '';
    expect(html).toContain('룩 a');
    expect(html).not.toContain('초안 룩');
    expect(html).not.toContain('아이디 없음');

    serverFetch.mockResolvedValue({ looks: 'oops' });
    expect(await render()).toBeNull();
    serverFetch.mockResolvedValue(null);
    expect(await render()).toBeNull();
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
