import { describe, expect, it } from 'vitest';
import {
  CATEGORY_INVALID,
  changeCount,
  clearedErrors,
  contentOf,
  DESCRIPTION_INVALID,
  draftErrors,
  externalUrlProblem,
  formOf,
  isDirty,
  LABEL_INVALID,
  PATH_INVALID,
  pathProblem,
  publicStateText,
  publishState,
  rowHasError,
  targetSummary,
  TOO_MANY_ITEMS,
  URL_INVALID,
} from './form';
import { blankNode, updateNode, type MenuNode } from './tree';
import type { NavDetail, NavItemPayload } from './types';

const item = (key: string, extra: Partial<NavItemPayload> = {}): NavItemPayload => ({
  key,
  parentKey: null,
  type: 'INTERNAL',
  labels: { ko: `${key} 국문`, en: `${key} en` },
  descriptions: null,
  url: `/${key}`,
  categoryId: null,
  displayOrder: 0,
  visible: true,
  primary: false,
  shortcut: false,
  ...extra,
});

const detail = (items: NavItemPayload[], published: number | null = null): NavDetail => ({
  version: 3,
  latestRevision: 5,
  publishedRevision: published,
  revision: {
    revisionNumber: 5,
    items,
    createdBy: '1',
    createdAt: '2026-11-27T00:00:00Z',
    publishedBy: null,
    publishedAt: null,
  },
});

// 서버는 트리 전위 순서로 준다 — shop(new, skirt), help(NONE), 외부 링크
const ITEMS: NavItemPayload[] = [
  item('shop', { primary: true, shortcut: true, descriptions: { ko: '전체 상품', en: 'All' } }),
  item('new', { parentKey: 'shop', displayOrder: 0 }),
  item('skirt', {
    parentKey: 'shop',
    displayOrder: 1,
    type: 'CATEGORY',
    url: null,
    categoryId: '42',
  }),
  item('help', { displayOrder: 1, type: 'NONE', url: null }),
  item('mag', {
    displayOrder: 2,
    type: 'EXTERNAL',
    url: 'https://magazine.example.com/a',
    visible: false,
  }),
];

const valid = (key: string, extra: Partial<MenuNode> = {}): MenuNode => ({
  ...blankNode(key),
  labelKo: '이름',
  labelEn: 'Name',
  path: '/shop',
  ...extra,
});

describe('formOf · contentOf', () => {
  it('전위 순서 목록 → 트리 → 같은 저장 본문으로 돌아온다(형제 순서는 0 부터 다시 매긴다)', () => {
    const tree = formOf(detail(ITEMS));
    expect(tree.map((node) => node.key)).toEqual(['shop', 'help', 'mag']);
    expect(tree[0]?.children.map((node) => node.key)).toEqual(['new', 'skirt']);
    expect(tree[0]?.descriptionKo).toBe('전체 상품');
    expect(tree[0]?.children[1]?.categoryId).toBe('42');
    expect(tree[2]?.externalUrl).toBe('https://magazine.example.com/a');
    expect(contentOf(tree)).toEqual(ITEMS);
  });

  it('형제 순서는 displayOrder 로 정하고, 상위를 못 찾은 항목은 최상위에 둔다', () => {
    const tree = formOf(
      detail([
        item('b', { displayOrder: 7 }),
        item('a', { displayOrder: 2 }),
        item('orphan', { parentKey: 'missing', displayOrder: 9 }),
      ]),
    );
    expect(tree.map((node) => node.key)).toEqual(['a', 'b', 'orphan']);
  });

  it('미등록(null)이면 빈 메뉴', () => {
    expect(formOf(null)).toEqual([]);
    expect(contentOf([])).toEqual([]);
  });

  it('지금 유형의 칸만 보내고, 두 언어 모두 빈 설명은 null, 하위의 1차 메뉴 · 바로가기는 끈다', () => {
    const tree: MenuNode[] = [
      valid('a', {
        type: 'CATEGORY',
        path: '/kept',
        categoryId: ' 7 ',
        descriptionKo: ' ',
        children: [valid('b', { primary: true, shortcut: true })],
      }),
    ];
    const [a, b] = contentOf(tree);
    expect(a).toMatchObject({ url: null, categoryId: '7', descriptions: null });
    expect(b).toMatchObject({ parentKey: 'a', displayOrder: 0, primary: false, shortcut: false });
  });
});

describe('isDirty · changeCount', () => {
  it('저장본과 같으면 깨끗하고, 바꾼 · 지운 · 더한 항목 수를 센다', () => {
    const base = detail(ITEMS);
    const tree = formOf(base);
    expect(isDirty(tree, base)).toBe(false);
    expect(changeCount(tree, base)).toBe(0);

    const edited = updateNode(tree, 'help', (node) => ({ ...node, labelKo: '고객센터' }));
    const withoutMag = edited.filter((node) => node.key !== 'mag');
    const added = [...withoutMag, valid('fresh')];
    expect(isDirty(added, base)).toBe(true);
    // help 수정 · mag 삭제 · fresh 추가
    expect(changeCount(added, base)).toBe(3);
  });

  it('미등록에서 항목을 더하면 바뀐 것이다', () => {
    expect(isDirty([], null)).toBe(false);
    expect(isDirty([valid('a')], null)).toBe(true);
  });
});

describe('pathProblem', () => {
  it.each(['/', '/shop', '/collections/new-in', '/a_b.c~d'])('%s 는 된다', (path) => {
    expect(pathProblem(path)).toBeNull();
  });

  it.each([
    '',
    'shop',
    '//evil.example.com',
    '/a//b',
    '/a/../b',
    '/a/./b',
    '/a%2Fb',
    '/a?x=1',
    '/a#top',
    '/한글',
    '/a b',
    '/a\\b',
    '/a+b',
    '/a:b',
    "/a'b",
    `/${'a'.repeat(512)}`,
  ])('%s 는 막는다(서버 경로 문자 집합 밖)', (path) => {
    expect(pathProblem(path)).toBe(PATH_INVALID);
  });
});

describe('externalUrlProblem', () => {
  it('https 공개 주소는 형식만 본다(허용 사이트는 서버가 정한다)', () => {
    expect(externalUrlProblem('https://magazine.example.com/issue/1')).toBeNull();
  });

  it.each([
    'http://example.com',
    'https://example.com:8443',
    'https://user@example.com',
    'https://example.com/?a=1',
    'https://example.com/#x',
    'https://example.com/a%20b',
    'https://127.0.0.1',
    'https://localhost',
    'https://shop.internal',
    'javascript:alert(1)',
    'https://x.example.com/a,b',
    'https://x.example.com/a+b',
    'https://x.example.com/a:b',
  ])('%s 는 막는다', (url) => {
    expect(externalUrlProblem(url)).toBe(URL_INVALID);
  });
});

describe('draftErrors', () => {
  it('정상 입력은 오류가 없다', () => {
    expect(draftErrors(formOf(detail(ITEMS)))).toEqual({});
  });

  it('표시명 · 설명 짝 · 유형별 대상 오류를 저장 본문 번호의 칸에 붙인다(숨김 항목도 같다)', () => {
    const tree: MenuNode[] = [
      valid('a', {
        labelKo: ' ',
        labelEn: 'x'.repeat(41),
        descriptionKo: '설명만 국문',
        children: [valid('b', { type: 'CATEGORY', categoryId: '0', visible: false })],
      }),
      valid('c', { type: 'EXTERNAL', externalUrl: 'http://x.example.com' }),
      valid('d', { type: 'NONE', path: '' }),
    ];
    const errors = draftErrors(tree);
    expect(errors['items.0.labels.ko']).toEqual([LABEL_INVALID]);
    expect(errors['items.0.labels.en']).toEqual([LABEL_INVALID]);
    expect(errors['items.0.descriptions.en']).toEqual([DESCRIPTION_INVALID]);
    expect(errors['items.0.descriptions.ko']).toBeUndefined();
    expect(errors['items.1.categoryId']).toEqual([CATEGORY_INVALID]);
    expect(errors['items.2.url']).toEqual([URL_INVALID]);
    expect(Object.keys(errors).some((key) => key.startsWith('items.3.'))).toBe(false);
    expect(rowHasError(errors, 1)).toBe(true);
    expect(rowHasError(errors, 3)).toBe(false);
  });

  it('60개를 넘으면 목록 오류다', () => {
    const tree = Array.from({ length: 61 }, (_, i) => valid(`k${i}`));
    expect(draftErrors(tree).items).toContain(TOO_MANY_ITEMS);
  });
});

describe('targetSummary', () => {
  it('유형별 대상 요약 — 카테고리는 이름을 알면 이름, 아니면 번호', () => {
    expect(targetSummary(valid('a'))).toBe('/shop');
    expect(
      targetSummary(valid('a', { type: 'EXTERNAL', externalUrl: 'https://m.example.com/x' })),
    ).toBe('m.example.com/x');
    expect(targetSummary(valid('a', { type: 'CATEGORY', categoryId: '42' }))).toBe('번호 42');
    expect(
      targetSummary(valid('a', { type: 'CATEGORY', categoryId: '42' }), () => '상의 > 저고리'),
    ).toBe('상의 > 저고리');
  });
});

describe('publicStateText · publishState', () => {
  it('미등록 · 미게시 · 최신 공개 · 이전 공개를 구분한다', () => {
    expect(publicStateText(null)).toContain('기본 메뉴');
    expect(publicStateText(detail(ITEMS))).toContain('아직 게시하지 않아');
    expect(publicStateText({ ...detail(ITEMS), publishedRevision: 5 })).toBe(
      '5번이 공개 중입니다(최신 리비전).',
    );
    expect(publicStateText(detail(ITEMS, 4))).toContain('최신 5번 초안은 게시 전');
  });

  it('저장하지 않은 입력이 있으면 게시할 수 없다', () => {
    const base = detail(ITEMS, 4);
    const tree = formOf(base);
    expect(publishState(tree, null)).toBe('none');
    expect(publishState(tree, base)).toBe('ready');
    expect(publishState([...tree, valid('new-one')], base)).toBe('dirty');
    expect(publishState(tree, { ...base, publishedRevision: 5 })).toBe('published');
  });
});

describe('clearedErrors', () => {
  const errors = {
    'items.1.labels.ko': ['a'],
    'items.1.descriptions.en': ['b'],
    'items.1.url': ['c'],
    'items.2.labels.ko': ['d'],
    form: ['e'],
  };

  it('고친 칸의 오류만 지우고 다른 줄 · 폼 전체 오류는 둔다', () => {
    const before = valid('a');
    expect(clearedErrors(errors, 1, before, { ...before, labelKo: '새 이름' })).toEqual({
      'items.1.descriptions.en': ['b'],
      'items.1.url': ['c'],
      'items.2.labels.ko': ['d'],
      form: ['e'],
    });
    // 설명은 짝 검사라 한 언어만 고쳐도 두 언어 오류를 지운다, 경로를 고치면 주소 오류를 지운다
    const next = clearedErrors(errors, 1, before, { ...before, descriptionKo: 'x', path: '/b' });
    expect(Object.keys(next)).toEqual(['items.1.labels.ko', 'items.2.labels.ko', 'form']);
    expect(clearedErrors(errors, 1, before, { ...before, visible: false })).toBe(errors);
  });
});
