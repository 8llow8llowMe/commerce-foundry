import { describe, expect, it } from 'vitest';
import {
  BODY_CHARACTER,
  createForm,
  editForm,
  LABEL_WITHOUT_URL,
  LINK_RULE,
  PERIOD_FORMAT,
  PERIOD_ORDER,
  PRODUCT_DUPLICATED,
  productCountMessage,
  sectionRequest,
  sectionWarnings,
  SINGLE_LINE_ONLY,
  TOO_LONG,
  unchangedRequest,
  type SectionForm,
} from './form';
import { localInputToIso } from './text';
import { mediaItem, productItem, section } from './test-fixtures';

const pick = section('PRODUCT_PICK', {
  title: '이번 주 추천',
  linkUrl: '/shop',
  linkLabel: '전체 보기',
  items: [productItem('11'), productItem('12', '숨긴 상품', false)],
});

const okRequest = (form: SectionForm) => {
  const built = sectionRequest(form);
  if (!built.ok) throw new Error(JSON.stringify(built.errors));
  return built.request;
};
const errorsOf = (form: SectionForm) => {
  const built = sectionRequest(form);
  if (built.ok) throw new Error('검증을 통과했다');
  return built.errors;
};

describe('editForm', () => {
  it('섹션 응답을 폼으로 — 빈 문구는 빈 글자, 상품 · 미디어는 나눠 담는다', () => {
    const form = editForm(pick, 'PRODUCT_PICK');
    expect(form).toMatchObject({
      sectionType: 'PRODUCT_PICK',
      title: '이번 주 추천',
      subtitle: '',
      body: '',
      linkUrl: '/shop',
      linkLabel: '전체 보기',
      visible: true,
      startAt: '',
      endAt: '',
      media: [],
    });
    expect(form.products).toEqual([
      { productId: '11', name: '상품 11', visible: true, availability: null, caption: null },
      { productId: '12', name: '숨긴 상품', visible: false, availability: null, caption: null },
    ]);
  });

  it('미디어의 대체 텍스트를 칸으로 꺼낸다(없으면 빈 글자)', () => {
    const form = editForm(
      section('HERO', { items: [mediaItem(), mediaItem({ altText: null }, '설명')] }),
      'HERO',
    );
    expect(form.media.map((item) => [item.altText, item.caption])).toEqual([
      ['샘플 사진', null],
      ['', '설명'],
    ]);
  });
});

describe('createForm', () => {
  it('새 섹션은 숨김 · 빈 칸으로 시작한다', () => {
    expect(createForm('STORY')).toMatchObject({ sectionType: 'STORY', visible: false, title: '' });
  });
});

describe('sectionRequest — 저장 요청 모양', () => {
  it('문구는 앞뒤 공백을 빼고 빈 칸은 null, 상품은 아이디 문자열로 순서대로 보낸다', () => {
    const form = { ...editForm(pick, 'PRODUCT_PICK'), title: '  이번 주 추천 ', linkLabel: ' ' };
    expect(okRequest(form)).toEqual({
      sectionType: 'PRODUCT_PICK',
      title: '이번 주 추천',
      subtitle: null,
      body: null,
      linkUrl: '/shop',
      linkLabel: null,
      visible: true,
      startAt: null,
      endAt: null,
      items: [
        { itemType: 'PRODUCT', productId: '11', caption: null },
        { itemType: 'PRODUCT', productId: '12', caption: null },
      ],
    });
  });

  it('미디어는 주소를 그대로, 고친 대체 텍스트를 실어 다시 보낸다', () => {
    const video = mediaItem({
      mediaType: { code: 'VIDEO', name: '영상', description: '' },
      pcUrl: 'https://cdn.example.test/v.mp4',
      pcPosterUrl: 'https://cdn.example.test/v.jpg',
    });
    const form = editForm(section('HERO', { items: [video] }), 'HERO');
    form.media[0]!.altText = ' 새 설명 ';
    expect(okRequest(form).items).toEqual([
      {
        itemType: 'MEDIA',
        media: {
          mediaType: 'VIDEO',
          pcUrl: 'https://cdn.example.test/v.mp4',
          mobileUrl: null,
          pcPosterUrl: 'https://cdn.example.test/v.jpg',
          mobilePosterUrl: null,
          altText: '새 설명',
        },
        caption: null,
      },
    ]);
  });

  it('폼에 보이지 않는 칸(상품 픽의 본문)은 저장된 값을 그대로 다시 보내고 검사하지 않는다', () => {
    const form = editForm({ ...pick, body: '예전 본문', subtitle: '예전 부제' }, 'PRODUCT_PICK');
    expect(okRequest(form)).toMatchObject({ body: '예전 본문', subtitle: '예전 부제' });
  });

  it('기간 칸을 고치지 않았으면 불러온 ISO 를 그대로(초 · 밀리초 유지), 고치면 바꾼 값을 보낸다', () => {
    const loaded = section('STORY', {
      startAt: '2026-11-01T00:00:30.500Z',
      endAt: '2026-12-01T00:00:00Z',
    });
    const form = editForm(loaded, 'STORY');
    expect(okRequest(form)).toMatchObject({
      startAt: '2026-11-01T00:00:30.500Z',
      endAt: '2026-12-01T00:00:00Z',
    });
    expect(okRequest({ ...form, endAt: '2027-01-01T00:00', startAt: '' })).toMatchObject({
      startAt: null,
      endAt: localInputToIso('2027-01-01T00:00'),
    });
  });
});

describe('sectionRequest — 입력 제약(BE 와 같다)', () => {
  const story = () => editForm(section('STORY'), 'STORY');

  it('글자 수 한도를 넘으면 그 칸에 오류를 붙이고 보내지 않는다(앞뒤 공백은 세지 않는다)', () => {
    expect(errorsOf({ ...story(), title: 'a'.repeat(201) })).toEqual({ title: [TOO_LONG.title] });
    expect(sectionRequest({ ...story(), title: ` ${'a'.repeat(200)} ` }).ok).toBe(true);
    expect(errorsOf({ ...story(), body: 'a'.repeat(2001) })).toEqual({ body: [TOO_LONG.body] });
    expect(errorsOf({ ...story(), linkUrl: `/${'a'.repeat(500)}` })).toEqual({
      linkUrl: [TOO_LONG.linkUrl],
    });
    expect(errorsOf({ ...story(), linkUrl: '/a', linkLabel: 'a'.repeat(51) })).toEqual({
      linkLabel: [TOO_LONG.linkLabel],
    });
  });

  it('본문만 개행 · 탭을 허용한다', () => {
    expect(sectionRequest({ ...story(), body: '첫 줄\n둘째 줄' }).ok).toBe(true);
    expect(errorsOf({ ...story(), title: '첫 줄\t둘째' })).toEqual({ title: [SINGLE_LINE_ONLY] });
    expect(errorsOf({ ...story(), body: 'a\u0000b' })).toEqual({ body: [BODY_CHARACTER] });
  });

  it('링크는 / 로 시작하는 상대 경로 또는 https 만, 문구만 있으면 거절한다', () => {
    expect(errorsOf({ ...story(), linkUrl: 'http://example.test' })).toEqual({
      linkUrl: [LINK_RULE],
    });
    expect(errorsOf({ ...story(), linkLabel: '더 보기' })).toEqual({
      linkLabel: [LABEL_WITHOUT_URL],
    });
  });

  it('노출 기간 — 종료가 시작보다 뒤여야 하고 형식이 틀리면 거절한다', () => {
    expect(
      errorsOf({ ...story(), startAt: '2026-11-01T00:00', endAt: '2026-11-01T00:00' }),
    ).toEqual({ period: [PERIOD_ORDER] });
    expect(errorsOf({ ...story(), startAt: '0000-01-01T00:00' })).toEqual({
      period: [PERIOD_FORMAT],
    });
  });

  it('대체 텍스트는 200자 · 한 줄 규칙을 그 줄에 붙인다', () => {
    const form = editForm(section('DETAIL', { items: [mediaItem(), mediaItem()] }), 'DETAIL');
    form.media[1]!.altText = 'a'.repeat(201);
    expect(errorsOf(form)).toEqual({ 'media.1.altText': [TOO_LONG.altText] });
  });

  it('상품 픽은 상품 1 ~ 20개, 같은 상품은 한 번만', () => {
    const empty = { ...editForm(pick, 'PRODUCT_PICK'), products: [] };
    expect(errorsOf(empty)).toEqual({ products: [productCountMessage('PRODUCT_PICK')] });
    const one = editForm(pick, 'PRODUCT_PICK').products[0]!;
    expect(errorsOf({ ...empty, products: [one, one] })).toEqual({
      products: [PRODUCT_DUPLICATED],
    });
    const many = Array.from({ length: 21 }, (_, index) => ({
      ...one,
      productId: String(index + 1),
    }));
    expect(errorsOf({ ...empty, products: many })).toEqual({
      products: [productCountMessage('PRODUCT_PICK')],
    });
  });
});

describe('unchangedRequest — 목록의 노출 바꾸기', () => {
  it('섹션 칸 · 아이템을 응답 그대로(LOOK 의 미디어 · 상품 섞인 순서까지) 싣고 노출만 바꾼다', () => {
    const look = section('LOOK', {
      startAt: '2026-11-01T00:00:00Z',
      items: [mediaItem(), productItem('21'), mediaItem({ altText: null })],
    });
    const request = unchangedRequest(look, { visible: false });
    expect(request).toMatchObject({
      sectionType: 'LOOK',
      title: '샘플 제목',
      visible: false,
      startAt: '2026-11-01T00:00:00Z',
    });
    expect(request.items.map((item) => item.itemType)).toEqual(['MEDIA', 'PRODUCT', 'MEDIA']);
    expect(request.items[1]).toEqual({ itemType: 'PRODUCT', productId: '21', caption: null });
  });
});

describe('sectionWarnings', () => {
  it('상품이 모두 숨김이면 메인에서 빠진다고 알린다', () => {
    const form = editForm(pick, 'PRODUCT_PICK');
    expect(sectionWarnings(form)).toEqual([]);
    expect(sectionWarnings({ ...form, products: form.products.slice(1) })).toHaveLength(1);
  });

  it('스토리의 제목 · 본문 · 미디어가 모두 없으면 메인에서 빠진다고 알린다', () => {
    expect(sectionWarnings({ ...createForm('STORY') })).toHaveLength(1);
    expect(sectionWarnings({ ...createForm('STORY'), body: '본문' })).toEqual([]);
  });
});
