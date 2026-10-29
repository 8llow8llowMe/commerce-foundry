'use client';

import { cx } from '@cf/ui';
import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { lookName, lookSummary } from '@/lib/look/form';
import type { LookAdmin } from '@/lib/look/types';

const linkButton =
  'text-sm underline-offset-4 hover:underline disabled:cursor-not-allowed disabled:opacity-50 aria-disabled:opacity-50';
const iconButton =
  'flex size-8 items-center justify-center rounded text-sm hover:bg-muted disabled:cursor-not-allowed disabled:opacity-30';

export type LookRowProps = {
  look: LookAdmin;
  position: number;
  total: number;
  /** 순서를 옮길 수 없다(폼 · 다른 요청 진행 중) */
  reorderLocked: boolean;
  /** 노출 · 수정 · 삭제를 할 수 없다(순서를 바꿨거나 다른 일이 진행 중) */
  actionsLocked: boolean;
  toggling: boolean;
  confirming: boolean;
  deleting: boolean;
  error: string | null;
  onMove: (to: number) => void;
  onToggle: () => void;
  onEdit: () => void;
  onAskDelete: () => void;
  onCancelDelete: () => void;
  onDelete: () => void;
};

/**
 * 룩 목록의 한 줄 — 대표 이미지 · 제목 · 구성 상품 수 · 노출. 손잡이를 끌거나(dnd-kit, 키보드 포함) 위 · 아래 버튼으로 순서를 바꾼다.
 * 룩 아이디 · 상품 아이디는 보이지 않는다(#305).
 */
export function LookRow({
  look,
  position,
  total,
  reorderLocked,
  actionsLocked,
  toggling,
  confirming,
  deleting,
  error,
  onMove,
  onToggle,
  onEdit,
  onAskDelete,
  onCancelDelete,
  onDelete,
}: LookRowProps) {
  const name = lookName(look);
  const id = look.lookId;
  const {
    attributes,
    listeners,
    setNodeRef,
    setActivatorNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({
    id,
    disabled: reorderLocked,
    attributes: { roleDescription: '순서 바꾸기 손잡이' },
  });

  return (
    <li
      ref={setNodeRef}
      style={{ transform: CSS.Translate.toString(transform), transition }}
      className={cx('relative', isDragging && 'z-10')}
    >
      <div
        className={cx(
          'flex flex-wrap items-center gap-x-4 gap-y-2 rounded-md bg-muted/50 px-3 py-3',
          isDragging && 'bg-muted shadow-md',
        )}
      >
        <div className="flex items-center gap-1">
          <button
            type="button"
            ref={setActivatorNodeRef}
            {...attributes}
            {...listeners}
            aria-label={`${name} 순서 바꾸기`}
            disabled={reorderLocked}
            className="flex size-8 cursor-grab touch-none items-center justify-center rounded text-muted-fg hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
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
            className={iconButton}
            aria-label={`${name} 위로`}
            data-focus={`up:${id}`}
            disabled={reorderLocked || position === 1}
            onClick={() => onMove(position - 2)}
          >
            ↑
          </button>
          <button
            type="button"
            className={iconButton}
            aria-label={`${name} 아래로`}
            data-focus={`down:${id}`}
            disabled={reorderLocked || position === total}
            onClick={() => onMove(position)}
          >
            ↓
          </button>
        </div>

        {/* 이미지 CDN 이 정해지지 않아 next/image 를 쓰지 않는다(메인 전시와 같다) */}
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={look.image.url}
          alt=""
          loading="lazy"
          decoding="async"
          className="size-12 shrink-0 rounded bg-muted object-cover"
        />

        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-sm">
            <span className="text-xs text-muted-fg">{position}</span>
            <span className="min-w-0 truncate font-medium">{name}</span>
            <span
              className={cx(
                'rounded px-1.5 py-0.5 text-xs',
                look.visible ? 'bg-primary text-primary-fg' : 'bg-muted',
              )}
            >
              {look.visible ? '노출 중' : '숨김'}
            </span>
          </div>
          <p className="text-xs text-muted-fg">{lookSummary(look)}</p>
        </div>

        <label className="flex cursor-pointer items-center gap-2 text-sm">
          <input
            type="checkbox"
            role="switch"
            checked={look.visible}
            disabled={actionsLocked || toggling}
            onChange={onToggle}
            aria-label={`${name} 고객에게 노출`}
            className="size-4 cursor-pointer accent-primary disabled:cursor-not-allowed"
          />
          {toggling ? '바꾸는 중…' : '노출'}
        </label>

        {confirming ? (
          <div className="flex items-center gap-3">
            <span className="text-sm">이 룩을 삭제할까요? 되돌릴 수 없습니다.</span>
            {/* 삭제 중에도 disabled 로 두지 않는다 — 포커스가 body 로 빠진다. 겹친 요청은 부모가 막는다 */}
            <button
              type="button"
              className={cx(linkButton, 'font-medium text-danger')}
              aria-disabled={deleting || undefined}
              onClick={onDelete}
            >
              {deleting ? '삭제 중…' : '삭제'}
            </button>
            <button
              type="button"
              className={linkButton}
              aria-disabled={deleting || undefined}
              data-focus={`confirm-cancel:${id}`}
              onClick={onCancelDelete}
            >
              취소
            </button>
          </div>
        ) : (
          <div className="flex items-center gap-3">
            <button
              type="button"
              className={linkButton}
              aria-label={`${name} 수정`}
              data-focus={`edit:${id}`}
              disabled={actionsLocked}
              onClick={onEdit}
            >
              수정
            </button>
            <button
              type="button"
              className={cx(linkButton, 'text-danger')}
              aria-label={`${name} 삭제`}
              data-focus={`delete:${id}`}
              disabled={actionsLocked}
              onClick={onAskDelete}
            >
              삭제
            </button>
          </div>
        )}
      </div>
      {error && (
        <p role="alert" className="mt-1 px-4 text-sm text-danger">
          {error}
        </p>
      )}
    </li>
  );
}
