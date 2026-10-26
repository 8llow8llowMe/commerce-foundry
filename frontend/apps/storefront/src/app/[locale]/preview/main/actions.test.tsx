import { ApiError } from '@cf/api-client';
import type { ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LocaleProvider } from '@/components/i18n/LocaleProvider';
import type { DisplayProductPickSection } from '@/lib/display/home-sections';
import { getDictionary } from '@/lib/i18n/dictionaries';
import { renderMainPreview } from './actions';

// 상품 픽 카드 안의 담기 · 링크가 라우터를 쓴다
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }) }));

const serverFetch = vi.hoisted(() => vi.fn());
vi.mock('@/lib/server-api', () => ({ serverFetch }));

beforeEach(() => {
  serverFetch.mockReset();
});

const won = (amount: number) => ({ amount, currency: 'KRW' as const });

const story = (sectionId: string, title: string, visible = true) => ({
  sectionId,
  sectionType: 'STORY',
  title,
  subtitle: null,
  body: null,
  linkUrl: null,
  linkLabel: null,
  visible,
  startAt: null,
  endAt: null,
  items: [],
});

const render = async (draft: unknown) => {
  const result = await renderMainPreview('ko', draft);
  if (!result.ok) throw new Error('미리보기 실패');
  return renderToStaticMarkup(
    <LocaleProvider locale="ko" prefix="" dict={getDictionary('ko')}>
      {result.node}
    </LocaleProvider>,
  );
};

describe('renderMainPreview', () => {
  it('노출 중인 섹션만 받은 순서대로 메인 블록으로 그린다', async () => {
    const html = await render({
      sections: [
        story('2', '둘째 스토리'),
        story('1', '첫째 스토리'),
        story('3', '숨긴 스토리', false),
      ],
    });
    expect(html.indexOf('둘째 스토리')).toBeLessThan(html.indexOf('첫째 스토리'));
    expect(html).not.toContain('숨긴 스토리');
    expect(serverFetch).not.toHaveBeenCalled();
  });

  it('상품은 그 언어로 토큰 없이 공개 상세에서 받고, 받지 못한 상품은 뺀다', async () => {
    serverFetch.mockImplementation(async (path: string) => {
      if (path === '/api/v1/products/11') {
        return {
          productId: '11',
          name: '보이는 상품',
          summary: null,
          categoryPath: [],
          thumbnail: null,
          images: [],
          descriptionHtml: null,
          salePrice: won(100000),
          listPrice: null,
          discountRate: null,
          saleStatus: { code: 'ON_SALE', name: '판매중', description: '' },
          maxQuantityPerOrder: null,
          optionGroups: [],
          skus: [],
          customOrder: null,
          preorder: null,
        };
      }
      throw new ApiError('notFound', 404, 'PRODUCT_001', null);
    });
    const result = await renderMainPreview('ko', {
      sections: [
        {
          ...story('1', '이번 주 추천'),
          sectionType: 'PRODUCT_PICK',
          items: [
            { itemType: 'PRODUCT', productId: '11', caption: null },
            { itemType: 'PRODUCT', productId: '12', caption: null },
          ],
        },
      ],
    });
    // 상품 픽은 async 서버 컴포넌트라 문자열로 그리지 않고 넘긴 블록을 본다
    const node = result.ok
      ? (result.node as ReactElement<{ sections: DisplayProductPickSection[] }>)
      : null;
    const [block] = node?.props.sections ?? [];
    expect(block?.type).toBe('PRODUCT_PICK');
    expect(block?.products.map((product) => product.name)).toEqual(['보이는 상품']);
    expect(serverFetch).toHaveBeenCalledWith(
      '/api/v1/products/11',
      { headers: { 'accept-language': 'ko' } },
      { anonymous: true },
    );
  });

  it('모르는 언어 · 모양이 다른 본문이면 그리지 않는다', async () => {
    expect(await renderMainPreview('xx', { sections: [] })).toEqual({ ok: false });
    expect(await renderMainPreview('ko', null)).toEqual({ ok: false });
  });
});
