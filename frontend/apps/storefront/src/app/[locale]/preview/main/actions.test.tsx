import { brand } from '@cf/brand';
import type { ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LocaleProvider } from '@/components/i18n/LocaleProvider';
import { ProductPickSection } from '@/components/home/ProductPickSection';
import {
  resolveHomeBlocks,
  type DisplayProductPickSection,
  type HomeBlock,
} from '@/lib/display/home-sections';
import { readPreviewDisplay } from '@/lib/display/preview';
import { getDictionary } from '@/lib/i18n/dictionaries';
import { renderMainPreview } from './actions';

// 상품 픽 카드 안의 담기 · 링크가 라우터를 쓴다
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }) }));

const serverFetch = vi.hoisted(() => vi.fn());
vi.mock('@/lib/server-api', () => ({ serverFetch }));

const fetchSpy = vi.fn<typeof fetch>();

beforeEach(() => {
  serverFetch.mockReset();
  fetchSpy.mockReset();
  vi.stubGlobal('fetch', fetchSpy);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

// 테스트용 가짜 데이터 — 실데이터를 쓰지 않는다(공개 저장소).

const story = (index: number, title: string) => ({
  sectionId: `preview-section-${index}`,
  sectionType: { code: 'STORY', name: '스토리', description: '' },
  title,
  subtitle: null,
  body: null,
  link: null,
  items: [],
});

const pick = {
  sectionId: 'preview-section-2',
  sectionType: { code: 'PRODUCT_PICK', name: '상품 픽', description: '' },
  title: '이번 주 추천',
  subtitle: null,
  body: null,
  link: { url: '/shop', label: null },
  items: [
    {
      itemId: 'preview-item-60',
      itemType: 'PRODUCT',
      media: null,
      product: {
        productId: '11',
        name: '미리보기 상품',
        thumbnail: null,
        hoverImage: null,
        salePrice: { amount: 90000, currency: 'KRW' },
        listPrice: null,
        discountRate: null,
        hasOptionSurcharge: true,
        saleStatus: { code: 'ON_SALE', name: '판매중', description: '' },
        customOrder: false,
        preorder: false,
      },
      caption: null,
    },
  ],
};

const blocksOf = (result: Awaited<ReturnType<typeof renderMainPreview>>) =>
  result.ok ? (result.node as ReactElement<{ sections: HomeBlock[] }>).props.sections : null;

const html = (node: unknown) =>
  renderToStaticMarkup(
    <LocaleProvider locale="ko" prefix="" dict={getDictionary('ko')}>
      {node as ReactElement}
    </LocaleProvider>,
  );

describe('renderMainPreview', () => {
  it('받은 응답 순서대로 메인 블록으로 그린다', async () => {
    const result = await renderMainPreview('ko', {
      sections: [story(1, '둘째 스토리'), story(0, '첫째 스토리')],
    });
    const markup = html(result.ok ? result.node : null);
    expect(markup.indexOf('둘째 스토리')).toBeLessThan(markup.indexOf('첫째 스토리'));
  });

  it('메인과 같은 경로(resolveHomeBlocks)로 블록을 만든다', async () => {
    const display = { sections: [story(0, '스토리'), pick] };
    const blocks = blocksOf(await renderMainPreview('ko', display));
    expect(blocks).toEqual(
      resolveHomeBlocks(
        readPreviewDisplay(display),
        brand.content.ko.home,
        getDictionary('ko').home,
      ),
    );
    // 상품 카드는 응답 값 그대로다(다시 받거나 근사하지 않는다)
    const product = blocks?.find((block) => block.type === 'PRODUCT_PICK') as
      DisplayProductPickSection | undefined;
    expect(product?.products).toEqual([pick.items[0]!.product]);
  });

  it('게이트웨이를 부르지 않는다', async () => {
    const result = await renderMainPreview('ko', { sections: [story(0, '스토리'), pick] });
    // 상품 픽은 async 서버 컴포넌트라 문자열로 그리지 않고 직접 불러 본다
    const rendered: string[] = [];
    for (const block of blocksOf(result) ?? []) {
      if (block.type === 'PRODUCT_PICK')
        rendered.push(html(await ProductPickSection({ section: block })));
    }
    expect(rendered.join('')).toContain('미리보기 상품');
    expect(serverFetch).not.toHaveBeenCalled();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('그릴 섹션이 없으면 메인처럼 브랜드 설정 메인이다', async () => {
    const blocks = blocksOf(await renderMainPreview('ko', { sections: [] }));
    expect(blocks).toEqual(brand.content.ko.home);
  });

  it('모르는 언어 · 모양이 다른 본문이면 그리지 않는다', async () => {
    expect(await renderMainPreview('xx', { sections: [] })).toEqual({ ok: false });
    expect(await renderMainPreview('ko', null)).toEqual({ ok: false });
    // 지난 메시지 모양(편집 상태 본문)도 그리지 않는다
    expect(await renderMainPreview('ko', { type: 'cf:main-preview:draft' })).toEqual({
      ok: false,
    });
  });
});
