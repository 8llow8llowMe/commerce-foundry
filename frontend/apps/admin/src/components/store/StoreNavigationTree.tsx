'use client';

import { cx } from '@cf/ui';
import {
  closestCenter,
  DndContext,
  DragOverlay,
  PointerSensor,
  useSensor,
  useSensors,
  type Announcements,
  type DragEndEvent,
  type DragMoveEvent,
  type DragOverEvent,
  type DragStartEvent,
} from '@dnd-kit/core';
import { SortableContext, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { useId, useState, type KeyboardEvent } from 'react';
import type { FormErrors } from '@/lib/api-errors';
import { nodeName, rowHasError, targetSummary } from '@/lib/store-navigation/form';
import {
  dragRows,
  findRow,
  flatten,
  heightOf,
  MOVE_PROBLEMS,
  positionText,
  projectDrop,
  relocate,
  type MenuNode,
  type MoveDirection,
  type MoveTarget,
} from '@/lib/store-navigation/tree';
import { TYPE_LABELS } from '@/lib/store-navigation/types';

/** 한 단계 들여쓰기 폭(px) — 끈 가로 거리를 단계로 바꿀 때도 쓴다 */
const INDENT = 24;

export const TREE_INSTRUCTIONS =
  '손잡이를 마우스나 손가락으로 끌어 옮깁니다. 오른쪽으로 끌면 바로 위 항목의 하위 메뉴가 됩니다. 키보드는 손잡이에서 Alt 와 위 · 아래 화살표로 형제 사이를, Alt 와 오른쪽 화살표로 바로 위 항목의 하위로, Alt 와 왼쪽 화살표로 상위 밖으로 옮깁니다.';

const ARROWS: Record<string, MoveDirection> = {
  ArrowUp: 'up',
  ArrowDown: 'down',
  ArrowRight: 'indent',
  ArrowLeft: 'outdent',
};

/** 손잡이 키 — Alt + 화살표만 옮기기로 쓴다. 나머지 키는 그대로 둔다 */
export function moveKey(event: Pick<KeyboardEvent, 'key' | 'altKey'>): MoveDirection | null {
  return event.altKey ? (ARROWS[event.key] ?? null) : null;
}

export type StoreNavigationTreeProps = {
  tree: MenuNode[];
  selectedKey: string | null;
  locale: 'ko' | 'en';
  errors: FormErrors;
  locked: boolean;
  categoryName: (id: string) => string | null;
  onSelect: (key: string) => void;
  onToggleVisible: (key: string, visible: boolean) => void;
  onMove: (key: string, direction: MoveDirection) => void;
  onDrop: (key: string, target: MoveTarget) => void;
  /** 놓을 수 없는 자리 · 결과 안내(aria-live) */
  onAnnounce: (message: string) => void;
};

/**
 * 메뉴 구조(트리). 손잡이를 끌어 형제 사이 · 다른 항목의 하위로 옮기고(dnd-kit, 새 라이브러리 없음),
 * 손잡이에서 `Alt+화살표` 로 같은 일을 한다. 끄는 동안 하위 항목은 함께 움직이고, 놓일 자리는 굵은 가로 막대로 보인다.
 * 깊이 3 · 형제 20 을 넘는 자리는 막대가 경고색이 되고 이유를 글자로 알린다(색만으로 알리지 않는다).
 */
export function StoreNavigationTree({
  tree,
  selectedKey,
  locale,
  errors,
  locked,
  categoryName,
  onSelect,
  onToggleVisible,
  onMove,
  onDrop,
  onAnnounce,
}: StoreNavigationTreeProps) {
  const dndId = useId();
  const [activeKey, setActiveKey] = useState<string | null>(null);
  const [overKey, setOverKey] = useState<string | null>(null);
  const [offsetX, setOffsetX] = useState(0);
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 4 } }));

  const all = flatten(tree);
  const indexOf = new Map(all.map((row, index) => [row.node.key, index]));
  const hidden = new Set<string>();
  if (activeKey) {
    const active = findRow(tree, activeKey);
    if (active) for (const row of flatten(active.node.children)) hidden.add(row.node.key);
  }
  const rows = all.filter((row) => !hidden.has(row.node.key));
  const keys = rows.map((row) => row.node.key);
  const activeRow = activeKey ? findRow(tree, activeKey) : null;

  const projection =
    activeKey && overKey && activeRow
      ? projectDrop(
          dragRows(tree, activeKey),
          activeKey,
          overKey,
          Math.round(offsetX / INDENT),
          heightOf(activeRow.node),
        )
      : null;
  // 형제 상한은 실제로 옮겨 봐야 안다
  const problem = projection
    ? projection.ok
      ? (() => {
          const tried = relocate(tree, activeKey ?? '', projection.target);
          return tried.ok ? null : tried.problem;
        })()
      : projection.problem
    : null;

  const reset = () => {
    setActiveKey(null);
    setOverKey(null);
    setOffsetX(0);
  };
  const name = (key: string | number) => {
    const row = findRow(tree, String(key));
    return row ? nodeName(row.node, locale) : '';
  };
  const announcements: Announcements = {
    onDragStart: ({ active }) => `${name(active.id)}을(를) 집었습니다.`,
    onDragOver: () => undefined,
    onDragMove: () => undefined,
    onDragEnd: () => undefined,
    onDragCancel: ({ active }) => `${name(active.id)} 옮기기를 취소했습니다.`,
  };

  const onDragStart = ({ active }: DragStartEvent) => {
    setActiveKey(String(active.id));
    setOverKey(String(active.id));
    setOffsetX(0);
  };
  const onDragMove = ({ delta }: DragMoveEvent) => setOffsetX(delta.x);
  const onDragOver = ({ over }: DragOverEvent) => setOverKey(over ? String(over.id) : null);
  const onDragEnd = ({ active }: DragEndEvent) => {
    const key = String(active.id);
    if (projection && projection.ok && !problem) onDrop(key, projection.target);
    else if (problem) onAnnounce(MOVE_PROBLEMS[problem]);
    reset();
  };

  if (tree.length === 0)
    return (
      <p className="text-sm text-muted-fg">
        메뉴가 없습니다. 비워 두고 게시하면 스토어프론트 헤더에 메뉴가 보이지 않습니다.
      </p>
    );

  return (
    <DndContext
      id={dndId}
      sensors={sensors}
      collisionDetection={closestCenter}
      onDragStart={onDragStart}
      onDragMove={onDragMove}
      onDragOver={onDragOver}
      onDragEnd={onDragEnd}
      onDragCancel={reset}
      accessibility={{ announcements, screenReaderInstructions: { draggable: TREE_INSTRUCTIONS } }}
    >
      <SortableContext items={keys} strategy={verticalListSortingStrategy}>
        <ul aria-label="메뉴 구조 (위가 먼저 보입니다)" className="flex flex-col">
          {rows.map((row) => {
            const index = indexOf.get(row.node.key) ?? 0;
            return (
              <TreeRow
                key={row.node.key}
                node={row.node}
                depth={row.depth}
                dropDepth={
                  row.node.key === activeKey && projection?.ok ? projection.depth : row.depth
                }
                dropBlocked={row.node.key === activeKey && problem !== null}
                selected={row.node.key === selectedKey}
                invalid={rowHasError(errors, index)}
                locale={locale}
                locked={locked}
                summary={targetSummary(row.node, categoryName)}
                position={positionText(tree, row.node.key, (item) => nodeName(item, locale))}
                onSelect={() => onSelect(row.node.key)}
                onToggleVisible={(visible) => onToggleVisible(row.node.key, visible)}
                onMove={(direction) => onMove(row.node.key, direction)}
              />
            );
          })}
        </ul>
      </SortableContext>
      {activeKey && problem && (
        <p className="mt-2 text-sm text-danger">놓을 수 없습니다 — {MOVE_PROBLEMS[problem]}</p>
      )}
      <DragOverlay dropAnimation={null}>
        {activeRow ? (
          <div className="bg-bg px-3 py-2 text-sm font-medium">
            {nodeName(activeRow.node, locale)}
            {activeRow.node.children.length > 0 && (
              <span className="ml-2 text-xs text-muted-fg">
                하위 {flatten(activeRow.node.children).length}개 함께
              </span>
            )}
          </div>
        ) : null}
      </DragOverlay>
    </DndContext>
  );
}

type TreeRowProps = {
  node: MenuNode;
  depth: number;
  /** 끄는 중이면 놓일 깊이(막대 들여쓰기) */
  dropDepth: number;
  dropBlocked: boolean;
  selected: boolean;
  invalid: boolean;
  locale: 'ko' | 'en';
  locked: boolean;
  summary: string;
  /** 스크린리더 위치 안내 — "2단계, SHOP 아래 1/3" */
  position: string;
  onSelect: () => void;
  onToggleVisible: (visible: boolean) => void;
  onMove: (direction: MoveDirection) => void;
};

function TreeRow({
  node,
  depth,
  dropDepth,
  dropBlocked,
  selected,
  invalid,
  locale,
  locked,
  summary,
  position,
  onSelect,
  onToggleVisible,
  onMove,
}: TreeRowProps) {
  const name = nodeName(node, locale);
  const {
    attributes,
    listeners,
    setNodeRef,
    setActivatorNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({
    id: node.key,
    disabled: locked,
    attributes: { roleDescription: '메뉴 옮기기 손잡이' },
  });
  const keyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    const direction = moveKey(event);
    if (!direction) return;
    event.preventDefault();
    onMove(direction);
  };

  return (
    <li
      ref={setNodeRef}
      style={{ transform: CSS.Translate.toString(transform), transition }}
      className="relative"
    >
      {isDragging ? (
        // 놓일 자리 — 굵은 가로 막대 하나. 들여쓰기가 곧 놓일 단계다
        <div className="flex h-10 items-center" style={{ paddingLeft: dropDepth * INDENT }}>
          <div className={cx('h-1 w-full', dropBlocked ? 'bg-danger' : 'bg-primary')} />
        </div>
      ) : (
        <div
          className={cx(
            'flex min-h-10 items-stretch gap-2 rounded-md pr-2',
            selected && 'bg-muted',
          )}
        >
          {Array.from({ length: depth }, (_, level) => (
            <span key={level} aria-hidden className="flex w-6 shrink-0 justify-center">
              <span className="w-0.5 bg-muted" />
            </span>
          ))}
          <button
            type="button"
            ref={setActivatorNodeRef}
            {...attributes}
            {...listeners}
            onKeyDown={keyDown}
            data-handle={node.key}
            aria-label={`${name} 옮기기 (${depth + 1}단계)`}
            disabled={locked}
            className="flex size-8 shrink-0 cursor-grab touch-none items-center justify-center self-center rounded text-muted-fg hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
          >
            <svg aria-hidden="true" viewBox="0 0 16 16" className="size-4 fill-current">
              <circle cx="5" cy="3" r="1.4" />
              <circle cx="11" cy="3" r="1.4" />
              <circle cx="5" cy="8" r="1.4" />
              <circle cx="11" cy="8" r="1.4" />
              <circle cx="5" cy="13" r="1.4" />
              <circle cx="11" cy="13" r="1.4" />
            </svg>
          </button>
          <button
            type="button"
            aria-pressed={selected}
            data-select={node.key}
            onClick={onSelect}
            className="flex min-w-0 flex-1 flex-wrap items-center gap-x-3 gap-y-0.5 py-2 text-left text-sm"
          >
            <span
              className={cx(
                'break-words',
                selected ? 'font-semibold' : 'font-medium',
                !node.visible && 'text-muted-fg',
              )}
            >
              {name}
            </span>
            <span className="sr-only">{position}</span>
            <span className="text-xs break-all text-muted-fg">
              {TYPE_LABELS[node.type]}
              {node.type !== 'NONE' && ` · ${summary}`}
            </span>
            {!node.visible && <span className="text-xs text-muted-fg">숨김</span>}
            {invalid && <span className="text-xs text-danger">확인 필요</span>}
          </button>
          <label className="flex shrink-0 cursor-pointer items-center gap-2 self-center text-xs">
            <input
              type="checkbox"
              role="switch"
              checked={node.visible}
              disabled={locked}
              onChange={(event) => onToggleVisible(event.currentTarget.checked)}
              aria-label={`${name} 노출`}
              className="size-4 cursor-pointer accent-primary disabled:cursor-not-allowed"
            />
            <span aria-hidden>{node.visible ? '보임' : '숨김'}</span>
          </label>
        </div>
      )}
    </li>
  );
}
