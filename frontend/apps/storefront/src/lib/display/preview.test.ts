import { describe, expect, it } from 'vitest';
import {
  isPreviewDisplay,
  parseOrigin,
  PREVIEW_DISPLAY,
  PREVIEW_LIMITS,
  previewFramePolicy,
  readPreviewDisplay,
} from './preview';

// 테스트용 가짜 데이터 — 실데이터를 쓰지 않는다(공개 저장소).

const won = (amount: number) => ({ amount, currency: 'KRW' });

const card = (overrides: Record<string, unknown> = {}) => ({
  productId: '11',
  name: '샘플 상품',
  thumbnail: {
    imageId: '1',
    url: 'https://cdn.example.test/1.jpg',
    width: 600,
    height: 800,
    altText: null,
  },
  hoverImage: null,
  salePrice: won(90000),
  listPrice: won(100000),
  discountRate: 10,
  hasOptionSurcharge: true,
  saleStatus: { code: 'ON_SALE', name: '판매중', description: '' },
  customOrder: false,
  preorder: false,
  ...overrides,
});

const productItem = (product: unknown) => ({
  itemId: 'preview-item-0',
  itemType: 'PRODUCT',
  media: null,
  product,
  caption: null,
});

const mediaItem = (media: Record<string, unknown> = {}) => ({
  itemId: 'preview-item-1',
  itemType: 'MEDIA',
  media: {
    mediaType: 'IMAGE',
    pcUrl: 'https://cdn.example.test/a.jpg',
    mobileUrl: null,
    pcPosterUrl: null,
    mobilePosterUrl: null,
    altText: '샘플 사진',
    ...media,
  },
  product: null,
  caption: null,
});

const section = (overrides: Record<string, unknown> = {}) => ({
  sectionId: 'preview-section-0',
  sectionType: { code: 'STORY', name: '스토리', description: '' },
  title: '제목',
  subtitle: null,
  body: null,
  link: null,
  items: [],
  ...overrides,
});

describe('readPreviewDisplay', () => {
  it('모양이 다르면 null 이다', () => {
    expect(readPreviewDisplay(null)).toBeNull();
    expect(readPreviewDisplay({})).toBeNull();
    expect(readPreviewDisplay({ sections: 'x' })).toBeNull();
    expect(readPreviewDisplay([])).toBeNull();
  });

  it('고객 메인 응답 모양을 그대로 읽고 상품 카드 값을 바꾸지 않는다', () => {
    const response = readPreviewDisplay({
      sections: [
        section({
          sectionType: { code: 'PRODUCT_PICK', name: '상품 픽', description: '' },
          link: { url: '/shop', label: '전체' },
          items: [productItem(card())],
        }),
      ],
    });
    expect(response?.sections[0]).toMatchObject({
      sectionId: 'preview-section-0',
      sectionType: { code: 'PRODUCT_PICK' },
      link: { url: '/shop', label: '전체' },
      items: [{ itemType: 'PRODUCT', media: null, product: card() }],
    });
  });

  it('모양이 다른 섹션 · 아이템 하나만 버린다', () => {
    const response = readPreviewDisplay({
      sections: [
        section({ sectionId: '<script>' }),
        section({ sectionType: 'STORY' }),
        null,
        section({
          sectionId: 'preview-section-3',
          items: [
            mediaItem({ pcUrl: 'javascript:alert(1)' }),
            mediaItem({ mobileUrl: 'data:image/png;base64,AAAA' }),
            { itemType: 'OTHER' },
            productItem(card({ productId: '../admin' })),
            productItem(card({ salePrice: { amount: 1.5, currency: 'KRW' } })),
            productItem(card({ salePrice: { amount: 100, currency: 'XYZ' } })),
            productItem(card({ saleStatus: null })),
            productItem(card({ thumbnail: { imageId: '1', url: 'javascript:x' } })),
          ],
        }),
      ],
    });
    expect(response?.sections).toHaveLength(1);
    const items = response?.sections[0]?.items ?? [];
    // 미디어는 http(s) 주소만, 모바일 주소가 맞지 않으면 그 칸만 비운다
    expect(items.map((item) => item.itemType)).toEqual(['MEDIA', 'PRODUCT']);
    expect(items[0]?.media?.mobileUrl).toBeNull();
    // 사진 주소가 맞지 않은 카드는 사진 없이 그린다
    expect(items[1]?.product?.thumbnail).toBeNull();
  });

  it('정가 · 할인율은 함께 있을 때만 싣는다', () => {
    const [item] =
      readPreviewDisplay({
        sections: [section({ items: [productItem(card({ discountRate: null }))] })],
      })?.sections[0]?.items ?? [];
    expect(item?.product).toMatchObject({ listPrice: null, discountRate: null });
  });

  it('섹션 · 아이템은 BE 상한까지만 읽는다', () => {
    const response = readPreviewDisplay({
      sections: Array.from({ length: PREVIEW_LIMITS.sections + 5 }, (_, i) =>
        section({
          sectionId: `preview-section-${i}`,
          items: Array.from({ length: PREVIEW_LIMITS.items + 5 }, () => mediaItem()),
        }),
      ),
    });
    expect(response?.sections).toHaveLength(PREVIEW_LIMITS.sections);
    expect(response?.sections[0]?.items).toHaveLength(PREVIEW_LIMITS.items);
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

describe('isPreviewDisplay', () => {
  const parent = {} as MessageEventSource;
  const admin = 'https://admin.example.test';
  const event = (overrides: Partial<Pick<MessageEvent, 'origin' | 'source' | 'data'>> = {}) => ({
    origin: admin,
    source: parent,
    data: { type: PREVIEW_DISPLAY, display: { sections: [] } },
    ...overrides,
  });

  it('허용한 어드민 origin 의 부모 창이 보낸 미리보기 응답만 받는다', () => {
    expect(isPreviewDisplay(event(), admin, parent)).toBe(true);
    expect(isPreviewDisplay(event({ origin: 'https://evil.example.test' }), admin, parent)).toBe(
      false,
    );
    expect(isPreviewDisplay(event({ source: {} as MessageEventSource }), admin, parent)).toBe(
      false,
    );
    expect(isPreviewDisplay(event({ data: { type: 'other' } }), admin, parent)).toBe(false);
    expect(isPreviewDisplay(event({ data: null }), admin, parent)).toBe(false);
    // 지난 메시지 모양(편집 상태)은 받지 않는다
    expect(
      isPreviewDisplay(
        event({ data: { type: 'cf:main-preview:draft', sections: [] } }),
        admin,
        parent,
      ),
    ).toBe(false);
    expect(isPreviewDisplay(event({ data: { type: PREVIEW_DISPLAY } }), admin, parent)).toBe(false);
  });
});
