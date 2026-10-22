import { describe, expect, it } from 'vitest';
import { findNode, flattenTree, nextDisplayOrder, parentCandidates, subtreeHeight } from './tree';
import type { CategoryNode } from './types';

const node = (
  categoryId: string,
  children: CategoryNode[] = [],
  patch: Partial<CategoryNode> = {},
): CategoryNode => ({
  categoryId,
  parentId: null,
  name: `c${categoryId}`,
  displayOrder: 0,
  visible: true,
  children,
  ...patch,
});

// 1 ─ 2 ─ 3      (3단계)
//   └ 4
// 5 (숨김) ─ 6
const tree = [
  node('1', [node('2', [node('3')]), node('4', [], { displayOrder: 3 })]),
  node('5', [node('6')], { visible: false, displayOrder: 1 }),
];
const ids = (rows: { node: CategoryNode }[]) => rows.map((row) => row.node.categoryId);

describe('flattenTree', () => {
  it('깊이 우선 순서로 펴고 깊이 · 경로를 단다', () => {
    const rows = flattenTree(tree);
    expect(ids(rows)).toEqual(['1', '2', '3', '4', '5', '6']);
    expect(rows.map((row) => row.depth)).toEqual([1, 2, 3, 2, 1, 2]);
    expect(rows[2]!.path).toEqual(['c1', 'c2', 'c3']);
  });

  it('숨김은 자신 · 숨긴 상위로 가른다', () => {
    const rows = flattenTree(tree);
    expect(rows.map((row) => row.hiddenBy)).toEqual([null, null, null, null, 'self', 'ancestor']);
  });
});

describe('subtreeHeight', () => {
  it('자신을 포함한 높이다', () => {
    expect(subtreeHeight(tree[0]!)).toBe(3);
    expect(subtreeHeight(node('x'))).toBe(1);
  });
});

describe('parentCandidates', () => {
  const rows = flattenTree(tree);

  it('새로 만들 때는 2단계까지만 상위가 된다', () => {
    expect(ids(parentCandidates(rows, null))).toEqual(['1', '2', '4', '5', '6']);
  });

  it('옮길 때는 자신 · 하위를 빼고, 하위 높이까지 더해 3단계를 넘지 않는 것만 남긴다', () => {
    // 2 는 하위가 있어 높이 2 — 1단계 아래에만 들어간다(자기 하위 3 은 제외)
    expect(ids(parentCandidates(rows, findNode(tree, '2')))).toEqual(['1', '5']);
    // 1 은 높이 3 — 어디에도 못 들어간다(최상위만)
    expect(ids(parentCandidates(rows, findNode(tree, '1')))).toEqual([]);
  });
});

describe('nextDisplayOrder', () => {
  it('형제 중 가장 큰 값 다음, 형제가 없으면 0 이다', () => {
    expect(nextDisplayOrder(tree[0]!.children)).toBe(4);
    expect(nextDisplayOrder([])).toBe(0);
  });
});

describe('findNode', () => {
  it('트리 어디서든 찾고, 없으면 null 이다', () => {
    expect(findNode(tree, '6')?.name).toBe('c6');
    expect(findNode(tree, '99')).toBeNull();
  });
});
