import type { HomeSection, LookSection, StorySection } from '@cf/ui';
import { describe, expect, it } from 'vitest';
import {
  linkRel,
  readDisplaySections,
  resolveHomeBlocks,
  safeHref,
  toHomeBlocks,
  type HomeLinkLabels,
} from './home-sections';
import type {
  DisplayElementItem,
  DisplayMediaItem,
  DisplayProductItem,
  DisplaySectionItem,
} from './types';

const labels: HomeLinkLabels = { more: '더 보기', viewAll: 'VIEW ALL', shop: 'SHOP' };

const image = (pcUrl: string, extra: Partial<DisplayMediaItem> = {}): DisplayMediaItem => ({
  mediaType: 'IMAGE',
  pcUrl,
  mobileUrl: null,
  pcPosterUrl: null,
  mobilePosterUrl: null,
  altText: null,
  ...extra,
});

const mediaItem = (media: DisplayMediaItem, caption: string | null = null): DisplayElementItem => ({
  itemId: `m-${media.pcUrl}`,
  itemType: 'MEDIA',
  media,
  product: null,
  caption,
});

const product = (productId: string): DisplayProductItem => ({
  productId,
  name: `상품 ${productId}`,
  thumbnail: null,
  hoverImage: null,
  salePrice: { amount: 1000, currency: 'KRW' },
  listPrice: null,
  discountRate: null,
  hasOptionSurcharge: false,
  saleStatus: { code: 'ON_SALE', name: '판매중', description: '' },
  customOrder: false,
  preorder: false,
});

const productItem = (productId: string): DisplayElementItem => ({
  itemId: `p-${productId}`,
  itemType: 'PRODUCT',
  media: null,
  product: product(productId),
  caption: null,
});

const section = (
  code: string,
  sectionId: string,
  extra: Partial<DisplaySectionItem> = {},
): DisplaySectionItem => ({
  sectionId,
  sectionType: { code, name: code, description: '' },
  title: null,
  subtitle: null,
  body: null,
  link: null,
  items: [],
  ...extra,
});

const LOOK: LookSection = {
  type: 'LOOK',
  id: 'shop-the-look',
  title: 'Shop the Look',
  body: null,
  media: { image: null, placeholder: '착장' },
  productIds: [],
  link: null,
};

const story = (id: string, layout: StorySection['layout']): StorySection => ({
  type: 'STORY',
  id,
  title: id,
  body: '',
  links: [],
  media: [],
  layout,
});

// 브랜드 설정 — STORY 레이아웃 순서(curtain → panel)와 LOOK 을 틀로 쓴다
const TEMPLATE: HomeSection[] = [story('collection', 'curtain'), LOOK, story('our', 'panel')];

describe('safeHref · linkRel', () => {
  it('스토어 안 상대 경로와 https 만 받는다', () => {
    expect(safeHref('/shop?categoryId=1')).toBe('/shop?categoryId=1');
    expect(safeHref('https://example.com/a')).toBe('https://example.com/a');
    for (const bad of [
      '//evil.test',
      '/\\evil',
      'javascript:alert(1)',
      'http://example.com',
      '/a b',
      'shop',
      'https://',
      null,
      42,
    ]) {
      expect(safeHref(bad)).toBeNull();
    }
  });

  it('외부(https) 링크만 noopener noreferrer 다', () => {
    expect(linkRel('https://example.com')).toBe('noopener noreferrer');
    expect(linkRel('/shop')).toBeUndefined();
  });
});

describe('toHomeBlocks — 매핑', () => {
  it('히어로: 첫 미디어의 PC · 모바일 사진, 한 줄 제목, 링크 + 쇼핑 보조 버튼', () => {
    const [hero] = toHomeBlocks(
      [
        section('HERO', '10', {
          title: '가을 컬렉션',
          subtitle: '부제',
          link: { url: '/collection', label: null },
          items: [
            mediaItem(
              image('https://cdn.test/pc.jpg', {
                mobileUrl: 'https://cdn.test/m.jpg',
                altText: '화보',
              }),
            ),
            mediaItem(image('https://cdn.test/second.jpg')),
          ],
        }),
      ],
      TEMPLATE,
      labels,
    );
    expect(hero).toEqual({
      type: 'HERO',
      id: 'display-10',
      title: ['가을 컬렉션'],
      subtitle: '부제',
      cta: { label: '더 보기', href: '/collection' },
      secondaryCta: { label: 'SHOP', href: '/shop' },
      desktop: { image: { src: 'https://cdn.test/pc.jpg', alt: '화보' }, placeholder: '' },
      mobile: { image: { src: 'https://cdn.test/m.jpg', alt: '화보' }, placeholder: '' },
      video: null,
    });
  });

  it('히어로: 링크가 없거나 /shop 이면 쇼핑 버튼 하나, 제목이 없으면 빈 줄 목록', () => {
    const blocks = toHomeBlocks(
      [
        section('HERO', '1', { items: [mediaItem(image('https://cdn.test/a.jpg'))] }),
        section('HERO', '2', {
          link: { url: '/shop', label: '쇼핑하기' },
          items: [mediaItem(image('https://cdn.test/b.jpg'))],
        }),
      ],
      TEMPLATE,
      labels,
    );
    expect(blocks[0]).toMatchObject({
      title: [],
      cta: { label: 'SHOP', href: '/shop' },
      secondaryCta: null,
    });
    expect(blocks[1]).toMatchObject({
      cta: { label: '쇼핑하기', href: '/shop' },
      secondaryCta: null,
    });
  });

  it('히어로 영상: PC 주소를 재생하고 포스터를 사진으로 둔다', () => {
    const [hero] = toHomeBlocks(
      [
        section('HERO', '1', {
          items: [
            mediaItem(
              image('https://cdn.test/v.mp4', {
                mediaType: 'VIDEO',
                pcPosterUrl: 'https://cdn.test/p.jpg',
                mobilePosterUrl: 'https://cdn.test/mp.jpg',
              }),
            ),
          ],
        }),
      ],
      TEMPLATE,
      labels,
    );
    expect(hero).toMatchObject({
      video: { src: 'https://cdn.test/v.mp4', poster: 'https://cdn.test/p.jpg' },
      desktop: { image: { src: 'https://cdn.test/p.jpg' } },
      mobile: { image: { src: 'https://cdn.test/mp.jpg' } },
    });
  });

  it('상품 픽: 응답의 상품 카드를 순서대로(중복 없이), 링크 문구가 없으면 VIEW ALL', () => {
    const [pick] = toHomeBlocks(
      [
        section('PRODUCT_PICK', '20', {
          title: 'The Edit',
          link: { url: '/shop?categoryId=3', label: null },
          items: [productItem('2'), productItem('1'), productItem('2')],
        }),
      ],
      TEMPLATE,
      labels,
    );
    expect(pick).toMatchObject({
      type: 'PRODUCT_PICK',
      id: 'display-20',
      title: 'The Edit',
      viewAll: { label: 'VIEW ALL', href: '/shop?categoryId=3' },
    });
    expect(pick && 'products' in pick && pick.products.map((p) => p.productId)).toEqual(['2', '1']);
  });

  it('상품 픽: 상품 카드의 hover 이미지(두 번째 정지 이미지)를 그대로 넘기고, 없으면 null 이다', () => {
    const thumb = (imageId: string) => ({
      imageId,
      url: `https://cdn.test/${imageId}.jpg`,
      width: 900,
      height: 1200,
      altText: null,
    });
    const withHover: DisplayElementItem = {
      ...productItem('1'),
      product: { ...product('1'), thumbnail: thumb('a'), hoverImage: thumb('b') },
    };
    const [pick] = toHomeBlocks(
      [section('PRODUCT_PICK', '20', { items: [withHover, productItem('2')] })],
      TEMPLATE,
      labels,
    );
    expect(pick && 'products' in pick && pick.products.map((p) => p.hoverImage)).toEqual([
      thumb('b'),
      null,
    ]);
  });

  it('스토리: 설정의 STORY 레이아웃을 순서대로 쓰고, 넘치면 asymmetric · 사진이 없으면 centered', () => {
    const media = [mediaItem(image('https://cdn.test/s.jpg', { altText: '컷' }), '캡션')];
    const blocks = toHomeBlocks(
      [
        section('STORY', '1', { title: 'A', body: '본문', items: media }),
        section('STORY', '2', { title: 'B', items: media }),
        section('STORY', '3', { title: 'C', items: media }),
        section('STORY', '4', { title: 'D' }),
      ],
      TEMPLATE,
      labels,
    );
    expect(blocks.map((block) => block.type === 'STORY' && block.layout)).toEqual([
      'curtain',
      'panel',
      'asymmetric',
      'centered',
    ]);
    expect(blocks[0]).toMatchObject({
      title: 'A',
      body: '본문',
      links: [],
      media: [
        { image: { src: 'https://cdn.test/s.jpg', alt: '컷' }, placeholder: '', caption: '캡션' },
      ],
    });
  });

  it('스토리: 링크는 하나, 위험한 주소는 버린다', () => {
    const blocks = toHomeBlocks(
      [
        section('STORY', '1', { title: 'A', link: { url: '/our-story', label: '이야기' } }),
        section('STORY', '2', { title: 'B', link: { url: 'javascript:alert(1)', label: 'x' } }),
      ],
      TEMPLATE,
      labels,
    );
    expect(blocks[0]).toMatchObject({ links: [{ label: '이야기', href: '/our-story' }] });
    expect(blocks[1]).toMatchObject({ links: [] });
  });

  it('미디어: 모바일 주소가 따로 있으면 mobileSrc, 영상은 포스터 사진이다', () => {
    const [detail] = toHomeBlocks(
      [
        section('DETAIL', '5', {
          title: 'Craft',
          body: '본문',
          items: [
            mediaItem(
              image('https://cdn.test/a.jpg', { mobileUrl: 'https://cdn.test/am.jpg' }),
              '자카드',
            ),
            mediaItem(
              image('https://cdn.test/v.mp4', {
                mediaType: 'VIDEO',
                pcPosterUrl: 'https://cdn.test/vp.jpg',
              }),
            ),
          ],
        }),
      ],
      TEMPLATE,
      labels,
    );
    expect(detail).toEqual({
      type: 'DETAIL',
      id: 'display-5',
      title: 'Craft',
      body: '본문',
      frame: null,
      points: [
        { title: '자카드', text: '', focus: null },
        { title: '', text: '', focus: null },
      ],
      media: [
        {
          image: { src: 'https://cdn.test/a.jpg', mobileSrc: 'https://cdn.test/am.jpg', alt: '' },
          placeholder: '',
          caption: '자카드',
        },
        { image: { src: 'https://cdn.test/vp.jpg', alt: '' }, placeholder: '', caption: null },
      ],
    });
  });

  it('룩: 응답 자리에 브랜드 설정의 LOOK 을 한 번만 그린다(#62 전)', () => {
    const blocks = toHomeBlocks([section('LOOK', '1'), section('LOOK', '2')], TEMPLATE, labels);
    expect(blocks).toEqual([LOOK]);
    expect(toHomeBlocks([section('LOOK', '1')], [], labels)).toEqual([]);
  });
});

describe('toHomeBlocks — 순서 · 생략', () => {
  it('응답 순서를 그대로 따른다', () => {
    const blocks = toHomeBlocks(
      [
        section('PRODUCT_PICK', 'b', { items: [productItem('1')] }),
        section('HERO', 'a', { items: [mediaItem(image('https://cdn.test/a.jpg'))] }),
        section('LOOK', 'c'),
        section('STORY', 'd', { title: '스토리' }),
      ],
      TEMPLATE,
      labels,
    );
    expect(blocks.map((block) => block.id)).toEqual([
      'display-b',
      'display-a',
      'shop-the-look',
      'display-d',
    ]);
  });

  it('비었거나 모르는 섹션은 건너뛴다', () => {
    const blocks = toHomeBlocks(
      [
        // 숨긴 상품이 빠져 빈 상품 픽
        section('PRODUCT_PICK', '1', { title: '빈 픽', items: [] }),
        // 그릴 사진이 없는 히어로 · 디테일
        section('HERO', '2', { title: '사진 없음' }),
        section('DETAIL', '3', { title: '사진 없음' }),
        // 제목 · 본문 · 사진이 모두 없는 스토리
        section('STORY', '4'),
        // 아직 모르는 유형
        section('BANNER', '5', {
          title: '새 블록',
          items: [mediaItem(image('https://cdn.test/x.jpg'))],
        }),
        // 주소 없는 미디어(모양 불일치)
        section('HERO', '6', {
          items: [mediaItem(image('https://cdn.test/v.mp4', { mediaType: 'VIDEO' }))],
        }),
        section('STORY', '7', { title: '남는 섹션' }),
      ],
      TEMPLATE,
      labels,
    );
    expect(blocks.map((block) => block.id)).toEqual(['display-7']);
  });

  it('빈 스토리를 건너뛰어도 STORY 레이아웃 순번은 응답 순서를 센다', () => {
    const blocks = toHomeBlocks(
      [
        section('STORY', '1'),
        section('STORY', '2', { title: 'B', items: [mediaItem(image('https://cdn.test/a.jpg'))] }),
      ],
      TEMPLATE,
      labels,
    );
    expect(blocks).toHaveLength(1);
    expect(blocks[0]).toMatchObject({ layout: 'panel' });
  });
});

describe('readDisplaySections', () => {
  it('sections 배열이 아니면 null, 모양이 다른 섹션 · 아이템은 뺀다', () => {
    expect(readDisplaySections(null)).toBeNull();
    expect(readDisplaySections('x')).toBeNull();
    expect(readDisplaySections({ sections: 'x' })).toBeNull();
    const read = readDisplaySections({
      sections: [
        {
          sectionId: '1',
          sectionType: { code: 'STORY' },
          items: [null, mediaItem(image('https://cdn.test/a.jpg'))],
        },
        { sectionId: 2, sectionType: { code: 'STORY' }, items: [] },
        { sectionId: '3', sectionType: null, items: [] },
        { sectionId: '4', sectionType: { code: 'STORY' } },
      ],
    });
    expect(read?.map((s) => s.sectionId)).toEqual(['1']);
    expect(read?.[0]?.items).toHaveLength(1);
  });
});

describe('resolveHomeBlocks — 실패 · 빈 응답', () => {
  it('조회 실패(null) · 모양 불일치면 브랜드 설정을 그대로 쓴다', () => {
    expect(resolveHomeBlocks(null, TEMPLATE, labels)).toBe(TEMPLATE);
    expect(resolveHomeBlocks({ unexpected: true }, TEMPLATE, labels)).toBe(TEMPLATE);
  });

  it('보이는 섹션이 없거나 모두 비었으면 브랜드 설정이다', () => {
    expect(resolveHomeBlocks({ sections: [] }, TEMPLATE, labels)).toBe(TEMPLATE);
    expect(
      resolveHomeBlocks(
        { sections: [section('PRODUCT_PICK', '1', { items: [] })] },
        TEMPLATE,
        labels,
      ),
    ).toBe(TEMPLATE);
  });

  it('그릴 섹션이 있으면 응답만 그린다(설정의 섹션을 섞지 않는다)', () => {
    const blocks = resolveHomeBlocks(
      { sections: [section('STORY', '1', { title: '하나' })] },
      TEMPLATE,
      labels,
    );
    expect(blocks.map((block) => block.id)).toEqual(['display-1']);
  });
});
