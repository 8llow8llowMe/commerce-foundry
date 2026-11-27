import { describe, expect, it } from 'vitest';
import {
  addNode,
  blankNode,
  dragRows,
  flatten,
  focusAfterRemove,
  moveBy,
  newKey,
  placementText,
  positionText,
  projectDrop,
  relocate,
  removeNode,
  validKey,
  type MenuNode,
} from './tree';

const node = (key: string, children: MenuNode[] = [], extra: Partial<MenuNode> = {}): MenuNode => ({
  ...blankNode(key),
  labelKo: key.toUpperCase(),
  labelEn: key,
  children,
  ...extra,
});

/** a(b(c)) · d · e */
const sample = (): MenuNode[] => [
  node('a', [node('b', [node('c')])], { primary: true }),
  node('d', [], { primary: true, shortcut: true }),
  node('e'),
];

const shape = (tree: MenuNode[]) =>
  flatten(tree).map((row) => `${'-'.repeat(row.depth)}${row.node.key}`);

describe('flatten', () => {
  it('전위 순서로 깊이 · 상위 · 형제 자리를 붙인다', () => {
    expect(
      flatten(sample()).map((row) => [row.node.key, row.depth, row.parentKey, row.index]),
    ).toEqual([
      ['a', 0, null, 0],
      ['b', 1, 'a', 0],
      ['c', 2, 'b', 0],
      ['d', 0, null, 1],
      ['e', 0, null, 2],
    ]);
  });
});

describe('moveBy', () => {
  it('위로 · 아래로는 형제 사이에서만 옮긴다', () => {
    const up = moveBy(sample(), 'e', 'up');
    expect(up.ok && shape(up.tree)).toEqual(['a', '-b', '--c', 'e', 'd']);
    expect(moveBy(sample(), 'a', 'up')).toEqual({ ok: false, problem: 'edge' });
    expect(moveBy(sample(), 'c', 'down')).toEqual({ ok: false, problem: 'edge' });
  });

  it('들여쓰기는 바로 위 형제의 마지막 하위로, 하위로 가면 1차 메뉴 · 바로가기를 끈다', () => {
    const result = moveBy(sample(), 'd', 'indent');
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(shape(result.tree)).toEqual(['a', '-b', '--c', '-d', 'e']);
    expect(result.cleared).toBe(true);
    const moved = flatten(result.tree).find((row) => row.node.key === 'd')?.node;
    expect(moved?.primary).toBe(false);
    expect(moved?.shortcut).toBe(false);
  });

  it('내어쓰기는 상위 바로 다음 자리로 옮긴다', () => {
    const result = moveBy(sample(), 'b', 'outdent');
    expect(result.ok && shape(result.tree)).toEqual(['a', 'b', '-c', 'd', 'e']);
    expect(result.ok && result.cleared).toBe(false);
    expect(moveBy(sample(), 'a', 'outdent')).toEqual({ ok: false, problem: 'edge' });
  });

  it('3단계를 넘는 들여쓰기는 막는다(하위까지 함께 센다)', () => {
    // a(b(c)) 아래로 d(f) 를 넣으면 4단계 — d 를 b 아래로 들여쓸 수 없다
    const tree = [node('a', [node('b', [node('c')]), node('d', [node('f')])])];
    expect(moveBy(tree, 'd', 'indent')).toEqual({ ok: false, problem: 'depth' });
    expect(moveBy(sample(), 'e', 'indent').ok).toBe(true);
  });
});

describe('relocate', () => {
  it('자기 하위 안으로는 옮기지 않는다', () => {
    expect(relocate(sample(), 'a', { parentKey: 'c', index: 0 })).toEqual({
      ok: false,
      problem: 'self',
    });
  });

  it('형제가 20개인 곳에는 더 넣지 않는다', () => {
    const full = [
      node(
        'p',
        Array.from({ length: 20 }, (_, i) => node(`k${i}`)),
      ),
      node('x'),
    ];
    expect(relocate(full, 'x', { parentKey: 'p', index: 0 })).toEqual({
      ok: false,
      problem: 'siblings',
    });
    // 같은 상위 안에서의 순서 바꾸기는 개수가 그대로라 된다
    expect(relocate(full, 'k3', { parentKey: 'p', index: 0 }).ok).toBe(true);
  });
});

describe('addNode', () => {
  it('맨 뒤에 빈 항목을 더하고 서버 형식의 새 key 를 준다', () => {
    const result = addNode(sample(), 'a');
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(validKey(result.key)).toBe(true);
    expect(shape(result.tree)).toEqual(['a', '-b', '--c', `-${result.key}`, 'd', 'e']);
  });

  it('3단계 아래 · 전체 60개 · 형제 20개를 넘지 않는다', () => {
    expect(addNode(sample(), 'c')).toEqual({ ok: false, problem: 'depth' });
    const sixty = Array.from({ length: 3 }, (_, i) =>
      node(
        `p${i}`,
        Array.from({ length: 19 }, (_, j) => node(`p${i}-${j}`)),
      ),
    );
    expect(addNode(sixty, null)).toEqual({ ok: false, problem: 'total' });
    const twenty = Array.from({ length: 20 }, (_, i) => node(`r${i}`));
    expect(addNode(twenty, null)).toEqual({ ok: false, problem: 'siblings' });
  });

  it('새 key 는 이미 있는 key 와 겹치지 않는다', () => {
    const first = newKey([]);
    const tree = [node(first)];
    expect(newKey(tree)).not.toBe(first);
  });
});

describe('removeNode', () => {
  it('하위까지 함께 지운다', () => {
    expect(shape(removeNode(sample(), 'b'))).toEqual(['a', 'd', 'e']);
  });
});

describe('projectDrop', () => {
  it('끄는 항목의 하위는 목록에서 빠지고 함께 움직인다', () => {
    expect(dragRows(sample(), 'a').map((row) => row.key)).toEqual(['a', 'd', 'e']);
  });

  it('오른쪽으로 끌면 앞 줄의 하위로, 가만히 두면 형제 자리로 놓인다', () => {
    const rows = dragRows(sample(), 'e');
    // e 를 d 자리 위로, 가로 이동 없이 — 최상위 1번째(0부터) 자리
    expect(projectDrop(rows, 'e', 'd', 0, 1)).toEqual({
      ok: true,
      depth: 0,
      target: { parentKey: null, index: 1 },
    });
    // 제자리에서 오른쪽으로 한 칸 — d 의 하위
    expect(projectDrop(rows, 'e', 'e', 1, 1)).toEqual({
      ok: true,
      depth: 1,
      target: { parentKey: 'd', index: 0 },
    });
  });

  it('깊이 상한은 끄는 항목의 높이를 뺀 값이다', () => {
    // a(b(c)) 를 e 뒤로 옮기며 오른쪽으로 끌어도 최상위에 머문다(높이 3)
    const rows = dragRows(sample(), 'a');
    expect(projectDrop(rows, 'a', 'e', 2, 3)).toEqual({
      ok: true,
      depth: 0,
      target: { parentKey: null, index: 2 },
    });
  });

  it('뒤 줄이 앞 줄보다 깊어 놓을 단계가 없으면 놓을 수 없다', () => {
    // x(y) 를 b 와 c 사이에 놓으려면 깊이 2 이상이어야 하지만 높이 2 라 1단계까지만 된다
    const tree = [node('a', [node('b', [node('c')])]), node('x', [node('y')])];
    const rows = dragRows(tree, 'x');
    expect(projectDrop(rows, 'x', 'c', 0, 2)).toEqual({ ok: false, problem: 'depth' });
  });
});

describe('placementText', () => {
  it('상위 이름과 자리로 알린다', () => {
    const result = moveBy(sample(), 'e', 'indent');
    expect(result.ok && placementText(result.tree, 'e', (n) => n.labelKo)).toBe(
      'E을(를) D 아래 1번째로 옮겼습니다.',
    );
    expect(placementText(sample(), 'd', (n) => n.labelKo)).toBe(
      'D을(를) 최상위 2번째로 옮겼습니다.',
    );
  });
});

describe('focusAfterRemove · positionText', () => {
  it('지운 뒤 포커스는 다음 형제 → 이전 형제 → 상위 → 없음(메뉴 추가 버튼)', () => {
    expect(focusAfterRemove(sample(), 'd')).toBe('e');
    expect(focusAfterRemove(sample(), 'e')).toBe('d');
    expect(focusAfterRemove(sample(), 'c')).toBe('b');
    expect(focusAfterRemove([node('only')], 'only')).toBeNull();
  });

  it('줄 위치를 단계 · 상위 · 형제 순번으로 알린다', () => {
    expect(positionText(sample(), 'd', (n) => n.labelKo)).toBe('최상위 2/3');
    expect(positionText(sample(), 'c', (n) => n.labelKo)).toBe('3단계, B 아래 1/1');
  });
});
