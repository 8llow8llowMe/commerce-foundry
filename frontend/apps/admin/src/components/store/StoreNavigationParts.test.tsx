import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { blankNode, type MenuNode } from '@/lib/store-navigation/tree';
import {
  ItemDetail,
  leavesScreen,
  MenuPreview,
  PublishConfirm,
  shownTree,
} from './StoreNavigationParts';
import { moveKey, StoreNavigationTree } from './StoreNavigationTree';

const node = (key: string, extra: Partial<MenuNode> = {}): MenuNode => ({
  ...blankNode(key),
  labelKo: `${key} 국문`,
  labelEn: `${key} EN`,
  path: `/${key}`,
  ...extra,
});

const TREE: MenuNode[] = [
  node('shop', {
    primary: true,
    shortcut: true,
    descriptionKo: '전체 상품 설명',
    descriptionEn: 'All products',
    children: [
      node('new', { children: [node('deep')] }),
      node('secret', { visible: false, children: [node('under-secret')] }),
    ],
  }),
  node('group', { type: 'NONE', primary: true }),
  node('mag', { type: 'EXTERNAL', externalUrl: 'https://m.example.com', shortcut: true }),
  node('hidden', { visible: false, primary: true }),
  node('broken', { labelEn: '', path: 'no-slash' }),
];

const block = (html: string, label: string) =>
  html.match(new RegExp(`<ul aria-label="${label}"[\\s\\S]*?</ul>(?=</div>)`))?.[0] ?? '';

describe('shownTree', () => {
  it('숨김 항목은 하위째 빠진다(서버 공개 트리와 같다)', () => {
    const keys = (nodes: MenuNode[]): string[] =>
      nodes.flatMap((item) => [item.key, ...keys(item.children)]);
    expect(keys(shownTree(TREE))).toEqual(['shop', 'new', 'deep', 'group', 'mag', 'broken']);
  });
});

describe('MenuPreview', () => {
  it('PC 는 1차 메뉴 줄(링크 없는 묶음 제목 제외)과 전체 메뉴의 대제목 · 설명 · 하위 · 3단계를 보인다', () => {
    const html = renderToStaticMarkup(<MenuPreview tree={TREE} device="pc" locale="ko" />);
    const line = block(html, 'PC 1차 메뉴 미리보기');
    expect(line).toContain('shop 국문');
    expect(line).not.toContain('group 국문');
    expect(line).not.toContain('hidden 국문');
    expect(html).toContain('전체 상품 설명');
    expect(html).toContain('new 국문');
    expect(html).toContain('deep 국문');
    expect(html).not.toContain('secret');
    expect(html).toContain('grid-cols-2');
    // 미리보기 글자는 이동하지 않는다
    expect(html).not.toContain('<a ');
  });

  it('모바일 · 영문은 바로가기 줄과 1열 메뉴, 그 언어 글자만 쓰고 빈 표시명 · 틀린 경로는 확인 필요로 둔다', () => {
    const html = renderToStaticMarkup(<MenuPreview tree={TREE} device="mobile" locale="en" />);
    const line = block(html, '모바일 바로가기 미리보기');
    expect(line).toContain('shop EN');
    expect(line).toContain('mag EN');
    expect(line).not.toContain('group EN');
    expect(html).toContain('All products');
    expect(html).not.toContain('국문');
    expect(html).toContain('w-[390px]');
    expect(html).toContain('grid-cols-1');
    expect(html).toContain('(표시명 없음)');
    expect(html).toContain('확인 필요');
  });

  it('보일 메뉴가 없으면 빈 안내', () => {
    const html = renderToStaticMarkup(<MenuPreview tree={[]} device="pc" locale="ko" />);
    expect(html).toContain('노출할 메뉴가 없습니다.');
    expect(html).toContain('1차 메뉴 줄이 없습니다');
  });
});

describe('ItemDetail', () => {
  const render = (props: Partial<Parameters<typeof ItemDetail>[0]> = {}) =>
    renderToStaticMarkup(
      <ItemDetail
        node={node('shop')}
        depth={0}
        index={0}
        errors={{}}
        locked={false}
        categories={{ kind: 'failed' }}
        movable={{ up: false, down: true, indent: false, outdent: false }}
        canAddChild
        onChange={() => {}}
        onMove={() => {}}
        onAddChild={() => {}}
        onRemove={() => {}}
        {...props}
      />,
    );

  it('최상위만 1차 메뉴 · 바로가기 칸이 있고, 하위는 안내만 있다', () => {
    expect(render()).toContain('PC 1차 메뉴에 보이기');
    const child = render({ depth: 1 });
    expect(child).not.toContain('PC 1차 메뉴에 보이기');
    expect(child).toContain('최상위 메뉴에서만 켤 수 있습니다');
  });

  it('옮길 수 없는 방향의 위치 버튼은 꺼져 있다', () => {
    const html = render();
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*aria-label="shop 국문 위로"/);
    expect(html).not.toMatch(/<button[^>]*disabled=""[^>]*aria-label="shop 국문 아래로"/);
  });

  it('카테고리 목록을 못 불러오면 번호 입력, 불러오면 고르기 목록(숨김 표시, 목록에 없는 번호 유지)', () => {
    const fallback = render({ node: node('c', { type: 'CATEGORY', categoryId: '42' }) });
    expect(fallback).toContain('카테고리 번호');
    expect(fallback).toContain('value="42"');
    const select = render({
      node: node('c', { type: 'CATEGORY', categoryId: '99' }),
      categories: {
        kind: 'ready',
        options: [
          { id: '1', label: '상의', hidden: false },
          { id: '2', label: '상의 > 저고리', hidden: true },
        ],
      },
    });
    expect(select).toContain('<select');
    expect(select).toContain('상의 &gt; 저고리 (숨김)');
    expect(select).toContain('번호 99 (목록에 없음)');
  });

  it('서버 · 화면 오류를 칸에 보인다', () => {
    const html = render({
      node: node('x', { type: 'EXTERNAL', externalUrl: 'https://m.example.com' }),
      index: 3,
      errors: { 'items.3.url': ['허용 안 됨'], 'items.3.labels.ko': ['표시명 오류'] },
    });
    expect(html).toContain('허용 안 됨');
    expect(html).toContain('표시명 오류');
  });
});

describe('moveKey', () => {
  it('Alt + 화살표만 옮기기다', () => {
    expect(moveKey({ key: 'ArrowUp', altKey: true })).toBe('up');
    expect(moveKey({ key: 'ArrowDown', altKey: true })).toBe('down');
    expect(moveKey({ key: 'ArrowRight', altKey: true })).toBe('indent');
    expect(moveKey({ key: 'ArrowLeft', altKey: true })).toBe('outdent');
    expect(moveKey({ key: 'ArrowUp', altKey: false })).toBeNull();
    expect(moveKey({ key: 'Enter', altKey: true })).toBeNull();
  });
});

describe('PublishConfirm', () => {
  it('게시할 리비전 번호와 바뀌는 범위를 알린다', () => {
    const html = renderToStaticMarkup(
      <PublishConfirm
        revisionNumber={4}
        pending={false}
        onCancel={() => {}}
        onPublish={() => {}}
      />,
    );
    expect(html).toContain('role="alertdialog"');
    expect(html).toContain('4번 초안을 게시할까요?');
  });
});

describe('leavesScreen', () => {
  const here = { origin: 'https://admin.example.com', pathname: '/store/navigation', search: '' };
  const link = (href: string, target = '') => ({ href, target });

  it('같은 출처의 다른 화면으로 가는 일반 클릭만 떠나는 것이다', () => {
    expect(leavesScreen(link('https://admin.example.com/store/footer'), here, false)).toBe(true);
    expect(leavesScreen(link('/practice'), here, false)).toBe(true);
    expect(leavesScreen(link('/store/navigation?x=1'), here, false)).toBe(true);
  });

  it('같은 화면 · 새 창 · 수정 키 · 다른 출처는 묻지 않는다', () => {
    expect(leavesScreen(link('/store/navigation'), here, false)).toBe(false);
    expect(leavesScreen(link('/store/navigation#top'), here, false)).toBe(false);
    expect(leavesScreen(link('/store/footer', '_blank'), here, false)).toBe(false);
    expect(leavesScreen(link('/store/footer'), here, true)).toBe(false);
    expect(leavesScreen(link('https://other.example.com/'), here, false)).toBe(false);
  });
});

describe('StoreNavigationTree', () => {
  it('이름 버튼에 단계 · 상위 · 순번을 스크린리더로 알리고, 고른 줄은 눌림 · 굵게 표시한다', () => {
    const html = renderToStaticMarkup(
      <StoreNavigationTree
        tree={TREE}
        selectedKey="new"
        locale="ko"
        errors={{ 'items.1.url': ['x'] }}
        locked={false}
        categoryName={() => null}
        onSelect={() => {}}
        onToggleVisible={() => {}}
        onMove={() => {}}
        onDrop={() => {}}
        onAnnounce={() => {}}
      />,
    );
    expect(html).toContain('<span class="sr-only">최상위 1/5</span>');
    expect(html).toContain('<span class="sr-only">2단계, shop 국문 아래 1/2</span>');
    const selected =
      html.match(/<button[^>]*aria-pressed="true"[^>]*>[\s\S]*?<\/button>/)?.[0] ?? '';
    expect(selected).toContain('font-semibold');
    expect(selected).toContain('new 국문');
    // 오류가 있는 줄(저장 본문 1번 = new)은 글자로 알린다
    expect(selected).toContain('확인 필요');
  });
});
