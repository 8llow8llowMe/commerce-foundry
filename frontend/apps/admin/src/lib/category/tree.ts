import { MAX_DEPTH, type CategoryNode } from './types';

/** 트리를 화면 순서(깊이 우선)대로 편 한 줄. 깊이는 1부터, `hiddenBy` 는 자신 또는 숨긴 상위 — 고객 메뉴에서 빠지는 이유다 */
export type CategoryRow = {
  node: CategoryNode;
  depth: number;
  /** 최상위부터 자신까지의 이름 */
  path: string[];
  hiddenBy: 'self' | 'ancestor' | null;
};

export function flattenTree(roots: CategoryNode[]): CategoryRow[] {
  const rows: CategoryRow[] = [];
  const walk = (nodes: CategoryNode[], depth: number, path: string[], ancestorHidden: boolean) => {
    for (const node of nodes) {
      const own = [...path, node.name];
      rows.push({
        node,
        depth,
        path: own,
        hiddenBy: ancestorHidden ? 'ancestor' : node.visible ? null : 'self',
      });
      walk(node.children, depth + 1, own, ancestorHidden || !node.visible);
    }
  };
  walk(roots, 1, [], false);
  return rows;
}

/** 자신을 포함한 높이(하위가 없으면 1) */
export function subtreeHeight(node: CategoryNode): number {
  return 1 + Math.max(0, ...node.children.map(subtreeHeight));
}

function contains(node: CategoryNode, categoryId: string): boolean {
  return (
    node.categoryId === categoryId || node.children.some((child) => contains(child, categoryId))
  );
}

/**
 * 상위로 고를 수 있는 카테고리. 옮길 카테고리(`moving`, 새로 만들면 null)가 그 아래에 들어가도 3단계를 넘지 않는 것만,
 * 그리고 자기 자신 · 자기 하위는 뺀다. 정본은 서버(`PRODUCT_040` 깊이 · `PRODUCT_041` 순환)다 — 고르기 쉽게 거를 뿐이다.
 */
export function parentCandidates(rows: CategoryRow[], moving: CategoryNode | null): CategoryRow[] {
  const height = moving ? subtreeHeight(moving) : 1;
  return rows.filter(
    (row) => row.depth + height <= MAX_DEPTH && !(moving && contains(moving, row.node.categoryId)),
  );
}

/** 새 카테고리의 노출 순서 기본값 — 형제 중 가장 큰 값 다음(형제가 없으면 0). 맨 뒤에 붙는다 */
export function nextDisplayOrder(siblings: CategoryNode[]): number {
  return siblings.length === 0 ? 0 : Math.max(...siblings.map((node) => node.displayOrder)) + 1;
}

export function findNode(roots: CategoryNode[], categoryId: string): CategoryNode | null {
  for (const node of roots) {
    if (node.categoryId === categoryId) return node;
    const found = findNode(node.children, categoryId);
    if (found) return found;
  }
  return null;
}
