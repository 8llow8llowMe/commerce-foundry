import { describe, expect, it } from 'vitest';
import { createForm, draftRequest, editForm } from './form';
import {
  isPreviewReady,
  NEW_SECTION_ID,
  parseOrigin,
  PREVIEW_READY,
  previewScale,
  previewSections,
} from './preview';
import { mediaItem, productItem, section } from './test-fixtures';

const hero = section('HERO', {
  sectionId: '1',
  title: '저장된 히어로',
  items: [mediaItem()],
});
const pick = section('PRODUCT_PICK', {
  sectionId: '2',
  title: '저장된 상품 픽',
  items: [productItem('11')],
});

describe('draftRequest', () => {
  it('저장과 달리 칸 오류가 있어도 지금 값을 싣는다', () => {
    const form = {
      ...editForm(pick, 'PRODUCT_PICK'),
      title: '  고친 제목  ',
      linkUrl: 'javascript:alert(1)',
      startAt: '잘못된 값',
      products: [],
    };
    expect(draftRequest(form)).toMatchObject({
      sectionType: 'PRODUCT_PICK',
      title: '고친 제목',
      // 링크 · 문구는 스토어프론트가 safeHref · 텍스트 노드로만 그린다
      linkUrl: 'javascript:alert(1)',
      startAt: null,
      items: [],
    });
  });

  it('미디어는 고친 대체 텍스트로, 고치지 않은 기간은 불러온 값 그대로 싣는다', () => {
    const loaded = section('HERO', {
      startAt: '2026-10-01T00:00:00.123456Z',
      items: [mediaItem({ altText: '처음 문구' })],
    });
    const form = editForm(loaded, 'HERO');
    form.media[0] = { ...form.media[0]!, altText: '고친 문구' };
    const request = draftRequest(form);
    expect(request.startAt).toBe('2026-10-01T00:00:00.123456Z');
    expect(request.items[0]).toMatchObject({ itemType: 'MEDIA', media: { altText: '고친 문구' } });
  });
});

describe('previewSections', () => {
  it('저장된 섹션은 화면 순서 그대로 받은 값을 싣는다', () => {
    const sections = previewSections([pick, hero], null);
    expect(sections.map((item) => item.sectionId)).toEqual(['2', '1']);
    expect(sections[0]).toMatchObject({
      title: '저장된 상품 픽',
      items: [{ itemType: 'PRODUCT', productId: '11' }],
    });
  });

  it('고치는 섹션은 그 자리에 저장 전 값을 싣는다', () => {
    const form = { ...editForm(hero, 'HERO'), title: '저장 전 제목' };
    const sections = previewSections([hero, pick], { mode: 'edit', sectionId: '1', form });
    expect(sections[0]).toMatchObject({ sectionId: '1', title: '저장 전 제목' });
    expect(sections[1]).toMatchObject({ sectionId: '2', title: '저장된 상품 픽' });
  });

  it('새 섹션은 임시 아이디로 맨 뒤에 둔다', () => {
    const form = { ...createForm('STORY'), title: '새 스토리', visible: true };
    const sections = previewSections([hero], { mode: 'create', form });
    expect(sections).toHaveLength(2);
    expect(sections[1]).toMatchObject({
      sectionId: NEW_SECTION_ID,
      title: '새 스토리',
      visible: true,
    });
  });
});

describe('parseOrigin', () => {
  it('http(s) 주소의 origin 만 받는다', () => {
    expect(parseOrigin('https://shop.example.test/path')).toBe('https://shop.example.test');
    expect(parseOrigin('http://localhost:3000')).toBe('http://localhost:3000');
    expect(parseOrigin('javascript:alert(1)')).toBeNull();
    expect(parseOrigin('not a url')).toBeNull();
    expect(parseOrigin('')).toBeNull();
    expect(parseOrigin(undefined)).toBeNull();
  });
});

describe('previewScale', () => {
  it('칸이 좁으면 줄이고 넓으면 그대로 둔다', () => {
    expect(previewScale(1440, 720)).toBe(0.5);
    expect(previewScale(390, 600)).toBe(1);
    expect(previewScale(1440, 0)).toBe(1);
  });
});

describe('isPreviewReady', () => {
  const frame = {} as MessageEventSource;
  const storefront = 'https://shop.example.test';
  const event = (overrides: Partial<Pick<MessageEvent, 'origin' | 'source' | 'data'>> = {}) => ({
    origin: storefront,
    source: frame,
    data: { type: PREVIEW_READY },
    ...overrides,
  });

  it('스토어프론트 origin 의 그 iframe 이 보낸 준비 알림만 믿는다', () => {
    expect(isPreviewReady(event(), storefront, frame)).toBe(true);
    expect(isPreviewReady(event({ origin: 'https://evil.example.test' }), storefront, frame)).toBe(
      false,
    );
    expect(isPreviewReady(event({ source: {} as MessageEventSource }), storefront, frame)).toBe(
      false,
    );
    expect(isPreviewReady(event(), storefront, null)).toBe(false);
    expect(isPreviewReady(event({ data: 'ready' }), storefront, frame)).toBe(false);
  });
});
