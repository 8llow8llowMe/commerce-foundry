import { moveItem } from '@/lib/display/order';
import { LIMITS, type NavType } from './types';

/**
 * 편집기의 메뉴 항목 한 개(트리). 저장 전까지 화면에만 있다 — 미리보기도 이 값으로 그린다.
 * 링크 대상은 유형마다 칸을 따로 둔다 — 유형을 바꿨다 되돌려도 입력이 남는다. 저장 본문에는 지금 유형의 칸만 간다.
 */
export type MenuNode = {
  /** 편집용 식별자(`[a-z0-9][a-z0-9-]{0,39}`). 운영자에게 보이지 않고 화면이 만든다 */
  key: string;
  type: NavType;
  labelKo: string;
  labelEn: string;
  descriptionKo: string;
  descriptionEn: string;
  /** INTERNAL — 언어 접두어 없는 경로(`/shop`) */
  path: string;
  /** EXTERNAL — https 주소 */
  externalUrl: string;
  /** CATEGORY — 카테고리 아이디(문자열) */
  categoryId: string;
  visible: boolean;
  primary: boolean;
  shortcut: boolean;
  children: MenuNode[];
};

/** 트리를 화면 순서(전위)로 편 한 줄. depth 는 0 부터(최상위 0) */
export type FlatRow = {
  node: MenuNode;
  depth: number;
  parentKey: string | null;
  /** 형제 안 자리(0 부터) */
  index: number;
  siblingCount: number;
};

export function flatten(tree: readonly MenuNode[]): FlatRow[] {
  const rows: FlatRow[] = [];
  const walk = (nodes: readonly MenuNode[], depth: number, parentKey: string | null) => {
    nodes.forEach((node, index) => {
      rows.push({ node, depth, parentKey, index, siblingCount: nodes.length });
      walk(node.children, depth + 1, node.key);
    });
  };
  walk(tree, 0, null);
  return rows;
}

/** 숨김 포함 전체 개수 */
export const countAll = (tree: readonly MenuNode[]): number => flatten(tree).length;

/** 자신을 포함한 높이(하위가 없으면 1) */
export function heightOf(node: MenuNode): number {
  return 1 + Math.max(0, ...node.children.map(heightOf));
}

/** 자신을 포함한 하위 전체 개수 */
export function sizeOf(node: MenuNode): number {
  return 1 + node.children.reduce((sum, child) => sum + sizeOf(child), 0);
}

export function findRow(tree: readonly MenuNode[], key: string): FlatRow | null {
  return flatten(tree).find((row) => row.node.key === key) ?? null;
}

const childrenOf = (tree: readonly MenuNode[], parentKey: string | null): readonly MenuNode[] =>
  parentKey === null ? tree : (findRow(tree, parentKey)?.node.children ?? []);

/** key 항목만 바꾼 새 트리 */
export function updateNode(
  tree: readonly MenuNode[],
  key: string,
  change: (node: MenuNode) => MenuNode,
): MenuNode[] {
  return tree.map((node) =>
    node.key === key ? change(node) : { ...node, children: updateNode(node.children, key, change) },
  );
}

/** key 항목(하위 포함)을 뺀 새 트리 */
export function removeNode(tree: readonly MenuNode[], key: string): MenuNode[] {
  return tree
    .filter((node) => node.key !== key)
    .map((node) => ({ ...node, children: removeNode(node.children, key) }));
}

function insertNode(
  tree: readonly MenuNode[],
  parentKey: string | null,
  index: number,
  inserted: MenuNode,
): MenuNode[] {
  if (parentKey === null) {
    const next = [...tree];
    next.splice(index, 0, inserted);
    return next;
  }
  return tree.map((node) =>
    node.key === parentKey
      ? { ...node, children: insertNode(node.children, null, index, inserted) }
      : { ...node, children: insertNode(node.children, parentKey, index, inserted) },
  );
}

const contains = (node: MenuNode, key: string): boolean =>
  node.key === key || node.children.some((child) => contains(child, key));

/** 옮길 수 없는 이유 — 화면 문구는 `MOVE_PROBLEMS` */
export type MoveProblem = 'depth' | 'siblings' | 'total' | 'self' | 'edge';

export const MOVE_PROBLEMS: Record<MoveProblem, string> = {
  depth: `메뉴는 ${LIMITS.depth}단계까지 둘 수 있어 그 자리에 놓을 수 없습니다.`,
  siblings: `한 메뉴 아래에는 ${LIMITS.siblings}개까지 둘 수 있어 그 자리에 놓을 수 없습니다.`,
  total: `메뉴는 숨김을 포함해 ${LIMITS.items}개까지 둘 수 있습니다.`,
  self: '항목을 자기 하위 메뉴 안으로 옮길 수 없습니다.',
  edge: '더 옮길 자리가 없습니다.',
};

export type MoveResult =
  | {
      ok: true;
      tree: MenuNode[];
      /** 하위로 옮겨 PC 1차 메뉴 · 모바일 바로가기를 껐다(최상위만 켤 수 있다) */
      cleared: boolean;
    }
  | { ok: false; problem: MoveProblem };

export type MoveTarget = { parentKey: string | null; index: number };

/**
 * key 항목(하위 포함)을 parentKey 아래 index 자리로 옮긴다. index 는 옮길 항목을 뺀 뒤의 형제 목록 기준이다.
 * 깊이 3 · 형제 20 을 넘으면 옮기지 않는다(서버 `STORE_NAV_106` · `102` 를 미리 막는다).
 */
export function relocate(tree: readonly MenuNode[], key: string, target: MoveTarget): MoveResult {
  const row = findRow(tree, key);
  if (!row) return { ok: false, problem: 'edge' };
  if (target.parentKey !== null && contains(row.node, target.parentKey))
    return { ok: false, problem: 'self' };
  const removed = removeNode(tree, key);
  const parentRow = target.parentKey === null ? null : findRow(removed, target.parentKey);
  if (target.parentKey !== null && !parentRow) return { ok: false, problem: 'edge' };
  const depth = parentRow ? parentRow.depth + 1 : 0;
  if (depth + heightOf(row.node) > LIMITS.depth) return { ok: false, problem: 'depth' };
  const siblings = childrenOf(removed, target.parentKey);
  if (siblings.length + 1 > LIMITS.siblings) return { ok: false, problem: 'siblings' };
  const index = Math.max(0, Math.min(target.index, siblings.length));
  const cleared = depth > 0 && (row.node.primary || row.node.shortcut);
  const moved = cleared ? { ...row.node, primary: false, shortcut: false } : row.node;
  return { ok: true, tree: insertNode(removed, target.parentKey, index, moved), cleared };
}

export type MoveDirection = 'up' | 'down' | 'indent' | 'outdent';

/**
 * 위로 · 아래로(형제 사이), 들여쓰기(바로 위 형제의 마지막 하위로), 내어쓰기(상위 바로 다음 자리로).
 * 키보드 `Alt+↑/↓/→/←` 와 상세 칸의 위치 버튼이 같이 쓴다.
 */
export function moveBy(
  tree: readonly MenuNode[],
  key: string,
  direction: MoveDirection,
): MoveResult {
  const row = findRow(tree, key);
  if (!row) return { ok: false, problem: 'edge' };
  const siblings = childrenOf(tree, row.parentKey);
  switch (direction) {
    case 'up':
      return row.index === 0
        ? { ok: false, problem: 'edge' }
        : relocate(tree, key, { parentKey: row.parentKey, index: row.index - 1 });
    case 'down':
      return row.index === siblings.length - 1
        ? { ok: false, problem: 'edge' }
        : relocate(tree, key, { parentKey: row.parentKey, index: row.index + 1 });
    case 'indent': {
      const previous = siblings[row.index - 1];
      return previous
        ? relocate(tree, key, { parentKey: previous.key, index: previous.children.length })
        : { ok: false, problem: 'edge' };
    }
    case 'outdent': {
      if (row.parentKey === null) return { ok: false, problem: 'edge' };
      const parent = findRow(tree, row.parentKey);
      if (!parent) return { ok: false, problem: 'edge' };
      return relocate(tree, key, { parentKey: parent.parentKey, index: parent.index + 1 });
    }
  }
}

const KEY_PATTERN = /^[a-z0-9][a-z0-9-]{0,39}$/;
let seq = 0;

/** 겹치지 않는 새 편집용 key — 서버 형식(`[a-z0-9][a-z0-9-]{0,39}`)을 지킨다 */
export function newKey(tree: readonly MenuNode[]): string {
  const taken = new Set(flatten(tree).map((row) => row.node.key));
  let key: string;
  do key = `item-${++seq}`;
  while (taken.has(key));
  return key;
}

export const validKey = (key: string) => KEY_PATTERN.test(key);

export function blankNode(key: string): MenuNode {
  return {
    key,
    type: 'INTERNAL',
    labelKo: '',
    labelEn: '',
    descriptionKo: '',
    descriptionEn: '',
    path: '',
    externalUrl: '',
    categoryId: '',
    visible: true,
    primary: false,
    shortcut: false,
    children: [],
  };
}

export type AddResult =
  { ok: true; tree: MenuNode[]; key: string } | { ok: false; problem: MoveProblem };

/** parentKey 아래(null 이면 최상위) 맨 뒤에 빈 항목을 더한다 */
export function addNode(tree: readonly MenuNode[], parentKey: string | null): AddResult {
  if (countAll(tree) >= LIMITS.items) return { ok: false, problem: 'total' };
  const parent = parentKey === null ? null : findRow(tree, parentKey);
  if (parentKey !== null && !parent) return { ok: false, problem: 'edge' };
  if (parent && parent.depth + 2 > LIMITS.depth) return { ok: false, problem: 'depth' };
  const siblings = childrenOf(tree, parentKey);
  if (siblings.length >= LIMITS.siblings) return { ok: false, problem: 'siblings' };
  const key = newKey(tree);
  return { ok: true, tree: insertNode(tree, parentKey, siblings.length, blankNode(key)), key };
}

/** 드래그 계산에 쓰는 줄 — 끄는 항목의 하위는 미리 뺀 목록이다(하위는 함께 움직인다) */
export type DragRow = { key: string; depth: number; parentKey: string | null };

export function dragRows(tree: readonly MenuNode[], activeKey: string | null): DragRow[] {
  const rows = flatten(tree);
  const active = activeKey ? rows.find((row) => row.node.key === activeKey) : undefined;
  const hidden = new Set(active ? flatten(active.node.children).map((row) => row.node.key) : []);
  return rows
    .filter((row) => !hidden.has(row.node.key))
    .map((row) => ({ key: row.node.key, depth: row.depth, parentKey: row.parentKey }));
}

export type DropProjection =
  { ok: true; depth: number; target: MoveTarget } | { ok: false; problem: MoveProblem };

/**
 * 끄는 중 놓일 자리. over 줄 자리로 옮긴 목록에서 앞 줄 · 뒤 줄 깊이 사이로, 가로로 끈 칸 수(depthOffset)만큼 들여쓴다.
 * 앞 줄보다 한 단계 깊게까지(그 항목의 하위로), 뒤 줄 깊이보다 얕게는 안 된다(뒤 줄의 상위가 바뀐다).
 * 깊이 상한은 끄는 항목의 높이를 뺀 값이다. dnd-kit 예제(SortableTree)의 방식이다.
 */
export function projectDrop(
  rows: readonly DragRow[],
  activeKey: string,
  overKey: string,
  depthOffset: number,
  activeHeight: number,
): DropProjection {
  const activeIndex = rows.findIndex((row) => row.key === activeKey);
  const overIndex = rows.findIndex((row) => row.key === overKey);
  const active = rows[activeIndex];
  if (!active || overIndex < 0) return { ok: false, problem: 'edge' };
  const moved = moveItem(rows, activeIndex, overIndex);
  const previous = moved[overIndex - 1];
  const next = moved[overIndex + 1];
  const limit = LIMITS.depth - activeHeight;
  const maxDepth = Math.min(previous ? previous.depth + 1 : 0, limit);
  const minDepth = next ? next.depth : 0;
  if (minDepth > maxDepth) return { ok: false, problem: 'depth' };
  const depth = Math.max(minDepth, Math.min(maxDepth, active.depth + depthOffset));

  let parentKey: string | null = null;
  if (depth > 0 && previous) {
    if (depth === previous.depth) parentKey = previous.parentKey;
    else if (depth > previous.depth) parentKey = previous.key;
    else
      parentKey =
        moved
          .slice(0, overIndex)
          .reverse()
          .find((row) => row.depth === depth)?.parentKey ?? null;
  }
  const index = moved
    .slice(0, overIndex)
    .filter((row) => row.parentKey === parentKey && row.depth === depth).length;
  return { ok: true, depth, target: { parentKey, index } };
}

/** 옮긴 결과 안내 — "SHOP 아래 2번째로 옮겼습니다" */
export function placementText(
  tree: readonly MenuNode[],
  key: string,
  nameOf: (node: MenuNode) => string,
): string {
  const row = findRow(tree, key);
  if (!row) return '';
  const name = nameOf(row.node);
  if (row.parentKey === null) return `${name}을(를) 최상위 ${row.index + 1}번째로 옮겼습니다.`;
  const parent = findRow(tree, row.parentKey);
  return `${name}을(를) ${parent ? nameOf(parent.node) : '상위 메뉴'} 아래 ${row.index + 1}번째로 옮겼습니다.`;
}

/** 지운 뒤 포커스를 둘 항목 — 다음 형제 → 이전 형제 → 상위. 없으면 null(최상위 마지막 하나를 지웠다 — "메뉴 추가" 버튼으로) */
export function focusAfterRemove(tree: readonly MenuNode[], key: string): string | null {
  const row = findRow(tree, key);
  if (!row) return null;
  const siblings = childrenOf(tree, row.parentKey);
  return siblings[row.index + 1]?.key ?? siblings[row.index - 1]?.key ?? row.parentKey;
}

/** 트리 줄 위치 안내(스크린리더) — "2단계, SHOP 아래 1/3" · "최상위 2/5" */
export function positionText(
  tree: readonly MenuNode[],
  key: string,
  nameOf: (node: MenuNode) => string,
): string {
  const row = findRow(tree, key);
  if (!row) return '';
  const place = `${row.index + 1}/${row.siblingCount}`;
  if (row.parentKey === null) return `최상위 ${place}`;
  const parent = findRow(tree, row.parentKey);
  return `${row.depth + 1}단계, ${parent ? nameOf(parent.node) : '상위 메뉴'} 아래 ${place}`;
}
