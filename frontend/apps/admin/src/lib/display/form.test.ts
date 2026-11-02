import { describe, expect, it } from 'vitest';
import {
  ALT_TEXT_REQUIRED,
  BODY_CHARACTER,
  createForm,
  draftRequest,
  editForm,
  LABEL_WITHOUT_URL,
  LINK_RULE,
  mediaCountMessage,
  MEDIA_URL_FORMAT,
  MEDIA_URL_REQUIRED,
  newImageItem,
  PERIOD_FORMAT,
  PERIOD_ORDER,
  PRODUCT_DUPLICATED,
  productCountMessage,
  sectionRequest,
  sectionWarnings,
  SINGLE_LINE_ONLY,
  titleTooWide,
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
      ['샘플 사진', ''],
      ['', '설명'],
    ]);
  });
});

describe('createForm', () => {
  it('새 섹션은 숨김 · 빈 칸으로 시작한다', () => {
    expect(createForm('STORY')).toMatchObject({ sectionType: 'STORY', visible: false, title: '' });
    expect(createForm('STORY').media).toEqual([]);
  });

  it('룩은 빈 이미지 주소 칸 하나로 시작한다(미디어 하한 1)', () => {
    expect(createForm('LOOK').media).toEqual([newImageItem()]);
  });
});

describe('sectionRequest — 룩(미디어를 주소로 넣는다, #419)', () => {
  const lookSection = section('LOOK', {
    body: '본문',
    items: [
      mediaItem({ mobileUrl: 'https://cdn.example.test/displays/a-mo.jpg' }),
      productItem('21'),
      mediaItem({ pcUrl: 'https://cdn.example.test/displays/b-pc.jpg', altText: '두 번째 사진' }),
    ],
  });

  it('제목 · 본문 · 링크를 고치고, 주소를 바꾼 이미지만 모바일 주소를 버린다(미디어 → 상품 순)', () => {
    const form = editForm(lookSection, 'LOOK');
    expect(form.media.map((item) => item.url)).toEqual([
      'https://cdn.example.test/displays/a-pc.jpg',
      'https://cdn.example.test/displays/b-pc.jpg',
    ]);
    form.media[0]!.url = ' https://cdn.example.test/displays/new.jpg ';
    const request = okRequest({ ...form, linkUrl: '/shop', linkLabel: '보러 가기' });
    expect(request).toMatchObject({ body: '본문', linkUrl: '/shop', linkLabel: '보러 가기' });
    expect(request.items).toEqual([
      {
        itemType: 'MEDIA',
        media: {
          mediaType: 'IMAGE',
          pcUrl: 'https://cdn.example.test/displays/new.jpg',
          mobileUrl: null,
          pcPosterUrl: null,
          mobilePosterUrl: null,
          altText: '샘플 사진',
        },
        caption: null,
      },
      {
        itemType: 'MEDIA',
        media: expect.objectContaining({ pcUrl: 'https://cdn.example.test/displays/b-pc.jpg' }),
        caption: null,
      },
      { itemType: 'PRODUCT', productId: '21', caption: null },
    ]);
  });

  it('주소를 고치지 않으면 모바일 주소를 그대로 둔다', () => {
    const request = okRequest(editForm(lookSection, 'LOOK'));
    expect(request.items[0]).toMatchObject({
      media: { mobileUrl: 'https://cdn.example.test/displays/a-mo.jpg' },
    });
  });

  it('상품 없이도 저장한다(룩의 상품 하한 0)', () => {
    const form = {
      ...createForm('LOOK'),
      media: [{ ...newImageItem(), url: 'https://cdn.example.test/l.jpg', altText: '룩 사진' }],
    };
    expect(okRequest(form).items).toHaveLength(1);
  });

  it('이미지 주소는 필수 · 500자 · 공백 금지, 이미지 수는 1 ~ 10개다', () => {
    const base = createForm('LOOK');
    expect(errorsOf(base)).toEqual({
      'media.0.url': [MEDIA_URL_REQUIRED],
      'media.0.altText': [ALT_TEXT_REQUIRED],
    });
    const withUrl = (url: string) => ({ ...newImageItem(), url, altText: '룩 사진' });
    expect(errorsOf({ ...base, media: [withUrl('https://cdn.example.test/a b.jpg')] })).toEqual({
      'media.0.url': [MEDIA_URL_FORMAT],
    });
    expect(
      errorsOf({ ...base, media: [withUrl(`https://cdn.example.test/${'a'.repeat(500)}`)] }),
    ).toEqual({ 'media.0.url': [TOO_LONG.mediaUrl] });
    expect(errorsOf({ ...base, media: [] })).toEqual({ media: [mediaCountMessage('LOOK')] });
    const eleven = Array.from({ length: 11 }, () => withUrl('https://cdn.example.test/a.jpg'));
    expect(errorsOf({ ...base, media: eleven })).toEqual({ media: [mediaCountMessage('LOOK')] });
  });

  it('이미지는 대체 텍스트가 있어야 저장한다(#71) — 영상은 아니다', () => {
    const hero = editForm(
      section('HERO', {
        items: [
          mediaItem({ altText: null }),
          mediaItem({
            mediaType: { code: 'VIDEO', name: '영상', description: '' },
            pcPosterUrl: 'https://cdn.example.test/displays/poster.jpg',
            altText: null,
          }),
        ],
      }),
      'HERO',
    );
    expect(errorsOf(hero)).toEqual({ 'media.0.altText': [ALT_TEXT_REQUIRED] });
    hero.media[0]!.altText = '  ';
    expect(errorsOf(hero)).toEqual({ 'media.0.altText': [ALT_TEXT_REQUIRED] });
    hero.media[0]!.altText = '가을 화보';
    expect(okRequest(hero).items).toHaveLength(2);
  });

  it('노출 켜기 · 끄기(unchangedRequest)는 대체 텍스트 없는 기존 이미지도 막지 않는다', () => {
    const hero = section('HERO', { items: [mediaItem({ altText: null })] });
    expect(unchangedRequest(hero, { visible: false }).items).toHaveLength(1);
  });

  it('히어로 · 스토리 · 디테일도 주소로 넣는다 — 새 섹션은 미디어 하한만큼 빈 칸으로 시작한다', () => {
    expect(createForm('HERO').media).toEqual([newImageItem()]);
    expect(createForm('DETAIL').media).toEqual([newImageItem()]);
    expect(createForm('STORY').media).toEqual([]);
    expect(errorsOf(createForm('DETAIL'))).toEqual({
      'media.0.url': [MEDIA_URL_REQUIRED],
      'media.0.altText': [ALT_TEXT_REQUIRED],
    });
  });

  it('히어로는 모바일 주소를 따로 고친다 — 비우면 null(PC 사진), 공백 · 500자 규칙은 같다', () => {
    const form = editForm(
      section('HERO', {
        items: [mediaItem({ mobileUrl: 'https://cdn.example.test/displays/a-mo.jpg' })],
      }),
      'HERO',
    );
    expect(form.media[0]!.mobileUrl).toBe('https://cdn.example.test/displays/a-mo.jpg');
    // PC 주소를 바꿔도 모바일 칸 값을 그대로 보낸다(룩과 다르다)
    form.media[0]!.url = 'https://cdn.example.test/displays/new-pc.jpg';
    expect(okRequest(form).items[0]).toMatchObject({
      media: {
        pcUrl: 'https://cdn.example.test/displays/new-pc.jpg',
        mobileUrl: 'https://cdn.example.test/displays/a-mo.jpg',
      },
    });
    form.media[0]!.mobileUrl = '  ';
    expect(okRequest(form).items[0]).toMatchObject({ media: { mobileUrl: null } });
    form.media[0]!.mobileUrl = 'https://cdn.example.test/a b.jpg';
    expect(errorsOf(form)).toEqual({ 'media.0.mobileUrl': [MEDIA_URL_FORMAT] });
  });

  it('룩 · 디테일은 모바일 칸을 보지 않는다', () => {
    const form = editForm(section('DETAIL', { items: [mediaItem()] }), 'DETAIL');
    form.media[0]!.mobileUrl = 'https://cdn.example.test/a b.jpg';
    expect(okRequest(form).items[0]).toMatchObject({ media: { mobileUrl: null } });
  });

  it('디테일은 클로즈업마다 설명(caption)을 고친다 — 200자 · 한 줄, 비우면 null', () => {
    const form = editForm(section('DETAIL', { items: [mediaItem({}, '예전 설명')] }), 'DETAIL');
    expect(form.media[0]!.caption).toBe('예전 설명');
    form.media[0]!.caption = ' 손바느질 깃 ';
    expect(okRequest(form).items[0]).toMatchObject({ caption: '손바느질 깃' });
    form.media[0]!.caption = '';
    expect(okRequest(form).items[0]).toMatchObject({ caption: null });
    form.media[0]!.caption = 'a'.repeat(201);
    expect(errorsOf(form)).toEqual({ 'media.0.caption': [TOO_LONG.caption] });
    form.media[0]!.caption = '첫 줄\n둘째 줄';
    expect(errorsOf(form)).toEqual({ 'media.0.caption': [SINGLE_LINE_ONLY] });
  });

  it('설명을 쓰지 않는 유형은 저장된 설명을 그대로 다시 보내고 검사하지 않는다', () => {
    const form = editForm(section('HERO', { items: [mediaItem({}, '예전 설명')] }), 'HERO');
    expect(okRequest(form).items[0]).toMatchObject({ caption: '예전 설명' });
  });

  it('미리보기 본문은 주소 칸이 빈 이미지(넣는 중)를 뺀다', () => {
    const form = {
      ...createForm('LOOK'),
      media: [newImageItem(), { ...newImageItem(), url: 'https://cdn.example.test/l.jpg' }],
    };
    expect(draftRequest(form).items).toEqual([
      expect.objectContaining({
        media: expect.objectContaining({ pcUrl: 'https://cdn.example.test/l.jpg' }),
      }),
    ]);
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
    expect(errorsOf({ ...story(), body: 'a'.repeat(2001) })).toEqual({ body: [TOO_LONG.body] });
    expect(errorsOf({ ...story(), linkUrl: `/${'a'.repeat(500)}` })).toEqual({
      linkUrl: [TOO_LONG.linkUrl],
    });
    expect(errorsOf({ ...story(), linkUrl: '/a', linkLabel: 'a'.repeat(51) })).toEqual({
      linkLabel: [TOO_LONG.linkLabel],
    });
  });

  it('제목은 유형별 너비 한도(2줄 규칙, #71)를 넘으면 막는다 — 한글 1 · 영문 등 0.5', () => {
    const hero = () => editForm(section('HERO', { items: [mediaItem()] }), 'HERO');
    expect(sectionRequest({ ...hero(), title: '가'.repeat(16) }).ok).toBe(true);
    expect(sectionRequest({ ...hero(), title: ` ${'a'.repeat(32)} ` }).ok).toBe(true);
    expect(errorsOf({ ...hero(), title: '가'.repeat(17) })).toEqual({
      title: [titleTooWide('HERO')],
    });
    // 스토리는 가장 좁은 칸(curtain) 기준이라 더 짧다
    expect(sectionRequest({ ...story(), title: '손끝의 계절' }).ok).toBe(true);
    expect(errorsOf({ ...story(), title: '손끝에서 시작되는 계절' })).toEqual({
      title: [titleTooWide('STORY')],
    });
    expect(titleTooWide('STORY')).toContain('한글 기준 9자');
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

  it('룩의 상품이 모두 숨김이면 함께 담기가 없다고 알린다(상품이 없으면 알리지 않는다)', () => {
    const form = editForm(
      section('LOOK', { items: [mediaItem(), productItem('1', '숨긴 상품', false)] }),
      'LOOK',
    );
    expect(sectionWarnings(form)).toHaveLength(1);
    expect(sectionWarnings({ ...form, products: [] })).toEqual([]);
  });

  it('스토리의 제목 · 본문 · 미디어가 모두 없으면 메인에서 빠진다고 알린다', () => {
    expect(sectionWarnings({ ...createForm('STORY') })).toHaveLength(1);
    expect(sectionWarnings({ ...createForm('STORY'), body: '본문' })).toEqual([]);
  });
});
