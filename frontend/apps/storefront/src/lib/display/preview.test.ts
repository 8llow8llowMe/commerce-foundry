import { describe, expect, it } from 'vitest';
import type { ProductDetail, ProductSku } from '@/lib/catalog/types';
import {
  isLiveAt,
  isPreviewDraft,
  PREVIEW_DRAFT,
  parseOrigin,
  PREVIEW_LIMITS,
  previewFramePolicy,
  previewProductIds,
  productCardOf,
  readPreviewDraft,
  toPreviewResponse,
  type PreviewSection,
} from './preview';

// 테스트용 가짜 데이터 — 실데이터를 쓰지 않는다(공개 저장소).

const won = (amount: number) => ({ amount, currency: 'KRW' as const });
const status = (code: 'ON_SALE' | 'SOLD_OUT') => ({ code, name: code, description: '' });

const rawSection = (overrides: Record<string, unknown> = {}) => ({
  sectionId: '1',
  sectionType: 'STORY',
  title: '제목',
  subtitle: null,
  body: null,
  linkUrl: null,
  linkLabel: null,
  visible: true,
  startAt: null,
  endAt: null,
  items: [],
  ...overrides,
});

const sku = (id: string, price: number, code: 'ON_SALE' | 'SOLD_OUT' = 'ON_SALE'): ProductSku => ({
  skuId: id,
  optionValueIds: [],
  additionalPrice: won(0),
  salePrice: won(price),
  saleStatus: status(code),
});

const image = (id: string) => ({
  imageId: id,
  url: `https://cdn.example.test/${id}.jpg`,
  width: 800,
  height: 1000,
  altText: null,
});

const detail = (overrides: Partial<ProductDetail> = {}): ProductDetail => ({
  productId: '11',
  name: '샘플 상품',
  summary: null,
  categoryPath: [],
  thumbnail: image('main'),
  images: [],
  descriptionHtml: null,
  salePrice: won(100000),
  listPrice: null,
  discountRate: null,
  saleStatus: status('ON_SALE'),
  maxQuantityPerOrder: null,
  optionGroups: [],
  skus: [sku('1', 100000)],
  customOrder: null,
  preorder: null,
  ...overrides,
});

describe('readPreviewDraft', () => {
  it('모양이 다르면 null 이다', () => {
    expect(readPreviewDraft(null)).toBeNull();
    expect(readPreviewDraft({})).toBeNull();
    expect(readPreviewDraft({ sections: 'x' })).toBeNull();
  });

  it('모양이 다른 섹션 · 아이템 하나만 버리고 빈 문구는 null 로 본다', () => {
    const sections = readPreviewDraft({
      sections: [
        rawSection({
          title: '  ',
          items: [
            { itemType: 'PRODUCT', productId: '11', caption: null },
            // 게이트웨이 경로에 들어가므로 숫자 아이디만 받는다
            { itemType: 'PRODUCT', productId: '../admin', caption: null },
            {
              itemType: 'MEDIA',
              media: { mediaType: 'IMAGE', pcUrl: 'javascript:alert(1)' },
              caption: null,
            },
            {
              itemType: 'MEDIA',
              media: { mediaType: 'IMAGE', pcUrl: 'https://cdn.example.test/a.jpg', altText: '' },
              caption: '캡션',
            },
          ],
        }),
        rawSection({ sectionId: '../x' }),
        'not a section',
      ],
    });
    expect(sections).toHaveLength(1);
    expect(sections?.[0]?.title).toBeNull();
    expect(sections?.[0]?.items).toEqual([
      { itemType: 'PRODUCT', productId: '11', caption: null },
      {
        itemType: 'MEDIA',
        media: {
          mediaType: 'IMAGE',
          pcUrl: 'https://cdn.example.test/a.jpg',
          mobileUrl: null,
          pcPosterUrl: null,
          mobilePosterUrl: null,
          altText: null,
        },
        caption: '캡션',
      },
    ]);
  });

  it('섹션 · 아이템은 BE 상한까지만 읽는다', () => {
    const items = Array.from({ length: 40 }, (_, index) => ({
      itemType: 'PRODUCT',
      productId: String(index + 1),
      caption: null,
    }));
    const sections = readPreviewDraft({
      sections: Array.from({ length: 60 }, (_, index) =>
        rawSection({ sectionId: String(index + 1), items }),
      ),
    });
    expect(sections).toHaveLength(PREVIEW_LIMITS.sections);
    expect(sections?.[0]?.items).toHaveLength(PREVIEW_LIMITS.items);
  });
});

describe('isLiveAt', () => {
  const now = Date.parse('2026-10-26T00:00:00Z');
  const at = (overrides: Partial<PreviewSection>) =>
    ({ ...rawSection(), ...overrides }) as PreviewSection;

  it('메인과 같이 노출 켜짐 · 시작 포함 · 종료 제외로 판정한다', () => {
    expect(isLiveAt(at({}), now)).toBe(true);
    expect(isLiveAt(at({ visible: false }), now)).toBe(false);
    expect(isLiveAt(at({ startAt: '2026-10-26T00:00:00Z' }), now)).toBe(true);
    expect(isLiveAt(at({ endAt: '2026-10-26T00:00:00Z' }), now)).toBe(false);
    expect(isLiveAt(at({ startAt: '2026-10-27T00:00:00Z' }), now)).toBe(false);
    expect(isLiveAt(at({ startAt: '읽을 수 없음' }), now)).toBe(false);
  });
});

describe('previewProductIds', () => {
  it('등장 순서로 중복 없이 상한까지 모은다', () => {
    const section = (ids: string[]) =>
      ({
        ...rawSection(),
        items: ids.map((productId) => ({ itemType: 'PRODUCT', productId, caption: null })),
      }) as PreviewSection;
    expect(previewProductIds([section(['2', '1']), section(['1', '3'])])).toEqual(['2', '1', '3']);
    const many = Array.from({ length: 30 }, (_, index) => String(index + 1));
    const sections = Array.from({ length: 5 }, (_, at) =>
      section(many.map((id) => `${at + 1}${id}`)),
    );
    expect(previewProductIds(sections)).toHaveLength(PREVIEW_LIMITS.products);
  });
});

describe('productCardOf', () => {
  it('판매중 SKU 의 가장 낮은 판매가를 고르고 더 비싼 SKU 가 있으면 추가금 표시를 켠다', () => {
    const card = productCardOf(
      detail({ skus: [sku('1', 149000), sku('2', 129000), sku('3', 99000, 'SOLD_OUT')] }),
    );
    expect(card.salePrice).toEqual(won(129000));
    expect(card.hasOptionSurcharge).toBe(true);
  });

  it('고른 판매가가 기본 판매가와 다르면 정가 · 할인율을 싣지 않는다', () => {
    const discounted = { listPrice: won(150000), discountRate: 20 };
    const same = productCardOf(
      detail({ ...discounted, salePrice: won(120000), skus: [sku('1', 120000), sku('2', 140000)] }),
    );
    expect(same.listPrice).toEqual(won(150000));
    expect(same.discountRate).toBe(20);
    const moved = productCardOf(
      detail({
        ...discounted,
        salePrice: won(120000),
        skus: [sku('1', 120000, 'SOLD_OUT'), sku('2', 140000)],
      }),
    );
    expect(moved.salePrice).toEqual(won(140000));
    expect(moved.listPrice).toBeNull();
    expect(moved.discountRate).toBeNull();
  });

  it('판매중 SKU 가 없으면 전체 SKU, SKU 도 없으면 기본 판매가다', () => {
    expect(productCardOf(detail({ skus: [sku('1', 99000, 'SOLD_OUT')] })).salePrice).toEqual(
      won(99000),
    );
    const card = productCardOf(detail({ skus: [] }));
    expect(card.salePrice).toEqual(won(100000));
    expect(card.hasOptionSurcharge).toBe(false);
  });

  it('hover 사진은 대표 다음의 첫 정지 이미지다(영상은 세지 않는다)', () => {
    const video = {
      ...image('video'),
      mediaType: 'VIDEO' as const,
      posterUrl: null,
      displayOrder: 1,
    };
    const still = {
      ...image('still'),
      mediaType: 'IMAGE' as const,
      posterUrl: null,
      displayOrder: 2,
    };
    expect(productCardOf(detail({ images: [video, still] })).hoverImage?.imageId).toBe('still');
    expect(productCardOf(detail({ images: [video] })).hoverImage).toBeNull();
    expect(productCardOf(detail({ thumbnail: null, images: [still] })).hoverImage).toBeNull();
  });

  it('맞춤 제작 · 프리오더는 여부만 싣는다', () => {
    const card = productCardOf(
      detail({
        customOrder: { notice: null, leadTimeDays: null, fields: [] },
        preorder: { expectedShipDate: null },
      }),
    );
    expect(card.customOrder).toBe(true);
    expect(card.preorder).toBe(true);
  });
});

describe('toPreviewResponse', () => {
  it('고객 메인 응답 모양으로 바꾸고 카드를 받지 못한 상품은 뺀다', () => {
    const [section] = readPreviewDraft({
      sections: [
        rawSection({
          sectionType: 'PRODUCT_PICK',
          linkUrl: '/shop',
          linkLabel: '전체',
          items: [
            { itemType: 'PRODUCT', productId: '11', caption: null },
            { itemType: 'PRODUCT', productId: '12', caption: null },
          ],
        }),
      ],
    })!;
    const card = productCardOf(detail());
    const response = toPreviewResponse([section!], new Map([['11', card]]));
    expect(response.sections[0]).toMatchObject({
      sectionId: 'preview-1',
      sectionType: { code: 'PRODUCT_PICK' },
      link: { url: '/shop', label: '전체' },
      items: [{ itemType: 'PRODUCT', product: card, media: null }],
    });
    expect(response.sections[0]?.items).toHaveLength(1);
  });

  it('링크 주소가 없으면 링크를 싣지 않는다', () => {
    const [section] = readPreviewDraft({ sections: [rawSection({ linkLabel: '문구만' })] })!;
    expect(toPreviewResponse([section!], new Map()).sections[0]?.link).toBeNull();
  });
});

describe('previewFramePolicy', () => {
  it('미리보기 경로에만 어드민 origin 의 iframe 을 허용한다', () => {
    expect(previewFramePolicy('/ko/preview/main', 'https://admin.example.test')).toBe(
      'frame-ancestors https://admin.example.test',
    );
    expect(previewFramePolicy('/en/preview', 'https://admin.example.test')).toBe(
      'frame-ancestors https://admin.example.test',
    );
    expect(previewFramePolicy('/ko/preview/main', null)).toBe("frame-ancestors 'none'");
    expect(previewFramePolicy('/ko', 'https://admin.example.test')).toBeNull();
    expect(previewFramePolicy('/ko/previews', 'https://admin.example.test')).toBeNull();
    expect(previewFramePolicy('/ko/shop/preview', 'https://admin.example.test')).toBeNull();
  });
});

describe('parseOrigin', () => {
  it('http(s) 주소의 origin 만 받는다', () => {
    expect(parseOrigin('https://admin.example.test/x')).toBe('https://admin.example.test');
    expect(parseOrigin('ftp://admin.example.test')).toBeNull();
    expect(parseOrigin(undefined)).toBeNull();
  });
});

describe('isPreviewDraft', () => {
  const parent = {} as MessageEventSource;
  const admin = 'https://admin.example.test';
  const event = (overrides: Partial<Pick<MessageEvent, 'origin' | 'source' | 'data'>> = {}) => ({
    origin: admin,
    source: parent,
    data: { type: PREVIEW_DRAFT, sections: [] },
    ...overrides,
  });

  it('허용한 어드민 origin 의 부모 창이 보낸 편집 상태만 받는다', () => {
    expect(isPreviewDraft(event(), admin, parent)).toBe(true);
    expect(isPreviewDraft(event({ origin: 'https://evil.example.test' }), admin, parent)).toBe(
      false,
    );
    expect(isPreviewDraft(event({ source: {} as MessageEventSource }), admin, parent)).toBe(false);
    expect(isPreviewDraft(event({ data: { type: 'other' } }), admin, parent)).toBe(false);
    expect(isPreviewDraft(event({ data: null }), admin, parent)).toBe(false);
  });
});
