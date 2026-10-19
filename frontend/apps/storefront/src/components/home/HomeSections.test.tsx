import { ApiError } from '@cf/api-client';
import type {
  DetailSection as DetailData,
  HeroSection as HeroData,
  StorySection as StoryData,
} from '@cf/ui';
import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ProductDetail, ProductListItem } from '@/lib/catalog/types';
import { DetailSection } from './DetailSection';
import { HeroSection } from './HeroSection';
import { LookSection } from './LookSection';
import { ProductPickSection } from './ProductPickSection';
import { StorySection } from './StorySection';

const serverFetch = vi.hoisted(() => vi.fn());
vi.mock('@/lib/server-api', () => ({ serverFetch }));

// 블록으로 감싼다 — 반환값이 함수면 Vitest 가 teardown 으로 보고 테스트 뒤에 인자 없이 부른다
beforeEach(() => {
  serverFetch.mockReset();
});

const slot = (placeholder: string) => ({ image: null, placeholder });

const HERO: HeroData = {
  type: 'HERO',
  id: 'hero',
  title: ['Line One', 'Line Two'],
  subtitle: '한 줄 문구',
  cta: { label: 'EXPLORE', href: '/collection' },
  secondaryCta: { label: 'SHOP', href: '/shop' },
  desktop: slot('PC 4:5'),
  mobile: slot('모바일 4:5'),
  video: null,
};

const listItem = (productId: string): ProductListItem => ({
  productId,
  name: `상품 ${productId}`,
  thumbnail: null,
  salePrice: { amount: 1000, currency: 'KRW' },
  listPrice: null,
  discountRate: null,
  hasOptionSurcharge: false,
  saleStatus: { code: 'ON_SALE', name: '판매중', description: '' },
  customOrder: false,
});

describe('HeroSection', () => {
  it('h1 제목을 줄마다 끊어(줄바꿈 없이) 그리고, 두 동작 링크 · 브레이크포인트별 빈 자리를 그린다', () => {
    const html = renderToStaticMarkup(<HeroSection section={HERO} />);
    expect(html).toMatch(
      /<h1[^>]*><span class="block whitespace-nowrap">Line One<\/span><span class="block whitespace-nowrap">Line Two<\/span><\/h1>/,
    );
    expect(html).toMatch(/<a [^>]*href="\/collection"[^>]*>EXPLORE<\/a>/);
    expect(html).toContain('href="/shop"');
    // 두 동작이면 모바일 반반 버튼
    expect(html).toContain('grid-cols-2');
    expect(html).toContain('모바일 4:5');
    expect(html).toContain('PC 4:5');
    expect(html).toContain('aspect-[4/5]');
  });

  it('보조 동작이 없으면 주 버튼 하나만 전폭으로 둔다', () => {
    const html = renderToStaticMarkup(<HeroSection section={{ ...HERO, secondaryCta: null }} />);
    expect(html).not.toContain('href="/shop"');
    expect(html).toContain('grid-cols-1');
  });

  it('화보가 있으면 PC 는 source, 모바일은 img 로 고르고 바로 받는다', () => {
    const html = renderToStaticMarkup(
      <HeroSection
        section={{
          ...HERO,
          desktop: { image: { src: 'https://img.test/pc.jpg', alt: 'pc' }, placeholder: '' },
          mobile: { image: { src: 'https://img.test/m.jpg', alt: '시즌 화보' }, placeholder: '' },
        }}
      />,
    );
    expect(html).toContain(
      '<source media="(min-width: 1024px)" srcSet="https://img.test/pc.jpg"/>',
    );
    expect(html).toMatch(/<img src="https:\/\/img.test\/m.jpg" alt="시즌 화보" loading="eager"/);
  });

  it('영상이 있으면 muted autoplay 이고 움직임 줄이기면 포스터를 보인다', () => {
    const html = renderToStaticMarkup(
      <HeroSection section={{ ...HERO, video: { src: '/v.mp4', poster: '/p.jpg' } }} />,
    );
    expect(html).toMatch(
      /<video[^>]*autoPlay=""[^>]*muted=""[^>]*class="[^"]*motion-reduce:hidden/,
    );
    expect(html).toMatch(/<img src="\/p.jpg"[^>]*motion-reduce:block/);
  });
});

describe('StorySection · DetailSection', () => {
  const story: StoryData = {
    type: 'STORY',
    id: 'story',
    title: 'Pink Forest',
    body: '본문',
    links: [{ label: 'SEE', href: '/collection' }],
    media: [slot('큰 컷'), slot('작은 컷')],
    layout: 'asymmetric',
  };

  it('비대칭은 이미지 두 칸, 가운데 배치는 3:2 사진 한 칸이다', () => {
    const asymmetric = renderToStaticMarkup(<StorySection section={story} />);
    expect(asymmetric).toContain('큰 컷');
    expect(asymmetric).toContain('작은 컷');
    expect(asymmetric).toContain('<h2 id="story-title"');

    const centered = renderToStaticMarkup(
      <StorySection section={{ ...story, layout: 'centered', media: [slot('넓은 컷')] }} />,
    );
    expect(centered).toContain('aspect-[3/2]');
    expect(centered).toContain('text-center');
  });

  it('디테일은 클로즈업 칸과 포인트를 번호 · 라벨 없이 그린다(#57 금지 목록)', () => {
    const detail: DetailData = {
      type: 'DETAIL',
      id: 'detail',
      title: 'Craft',
      body: '본문',
      points: [
        { title: '첫째', text: 'a' },
        { title: '둘째', text: 'b' },
      ],
      media: [slot('클로즈업 1'), slot('클로즈업 2'), slot('클로즈업 3')],
    };
    const html = renderToStaticMarkup(<DetailSection section={detail} />);
    expect(html.match(/aspect-\[3\/4\]/g)).toHaveLength(3);
    expect(html).toContain('첫째');
    expect(html).not.toMatch(/>0[12]</);
    expect(html).not.toContain('<ol');
  });
});

describe('ProductPickSection (서버 데이터)', () => {
  const section = {
    type: 'PRODUCT_PICK' as const,
    id: 'pick',
    title: 'The Edit',
    viewAll: { label: 'VIEW ALL', href: '/shop' },
    source: { categoryId: '7', limit: 2 },
  };

  it('상품 목록 API 에서 limit 개만 받아 카드로 그린다', async () => {
    serverFetch.mockResolvedValue({
      contents: [listItem('1'), listItem('2'), listItem('3')],
      hasNext: true,
    });
    const html = renderToStaticMarkup(await ProductPickSection({ section }));
    expect(serverFetch).toHaveBeenCalledWith(
      '/api/v1/products?categoryId=7&sortType=NEWEST&page=0&size=2',
    );
    expect(html.match(/href="\/products\//g)).toHaveLength(2);
    expect(html).toContain('href="/shop"');
  });

  it('못 받았거나 비었으면 섹션을 그리지 않는다', async () => {
    serverFetch.mockRejectedValue(new ApiError('unavailable', 503, 'GATEWAY_002', null));
    expect(await ProductPickSection({ section })).toBeNull();
    serverFetch.mockResolvedValue({ contents: [], hasNext: false });
    expect(await ProductPickSection({ section })).toBeNull();
  });
});

describe('LookSection (서버 데이터)', () => {
  const detail = (productId: string, code: 'ON_SALE' | 'SOLD_OUT', name: string) =>
    ({
      productId,
      name: `구성품 ${productId}`,
      thumbnail: null,
      salePrice: { amount: 89000, currency: 'KRW' },
      saleStatus: { code, name, description: '' },
      optionGroups: [],
      skus: [],
      customOrder: null,
    }) as unknown as ProductDetail;

  const section = {
    type: 'LOOK' as const,
    id: 'look',
    title: 'Shop the Look',
    body: null,
    media: slot('착장 4:5'),
    productIds: ['1', '2', '3'],
    link: { label: 'SHOP ALL', href: '/shop' },
  };

  it('구성품을 상세 API 로 받아 이름 · 가격 · 판매 상태를 보이고, 못 받은 것은 뺀다', async () => {
    serverFetch.mockImplementation(async (path: string) => {
      if (path.endsWith('/1')) return detail('1', 'ON_SALE', '판매중');
      if (path.endsWith('/2')) return detail('2', 'SOLD_OUT', '품절');
      throw new ApiError('notFound', 404, 'PRODUCT_001', null);
    });
    const html = renderToStaticMarkup(await LookSection({ section }));
    expect(html).toContain('href="/products/1"');
    expect(html).toContain('89,000원 · 품절');
    expect(html).not.toContain('/products/3');
  });

  it('구성품이 없으면 화보와 전체 보기 링크만 그린다', async () => {
    const html = renderToStaticMarkup(
      await LookSection({ section: { ...section, productIds: [] } }),
    );
    expect(serverFetch).not.toHaveBeenCalled();
    expect(html).toContain('착장 4:5');
    expect(html).not.toContain('/products/');
    expect(html).toContain('SHOP ALL');
  });
});
