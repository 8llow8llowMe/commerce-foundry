'use client';

import { apiFetch } from '@cf/api-client';
import { Button } from '@cf/ui';
import {
  closestCenter,
  DndContext,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  type Announcements,
  type DragEndEvent,
  type UniqueIdentifier,
} from '@dnd-kit/core';
import {
  SortableContext,
  sortableKeyboardCoordinates,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { useEffect, useId, useRef, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import { moveItem, sameOrder } from '@/lib/display/order';
import { DELETE_FAILED, lookOrderFailure, lookRowFailure, TOGGLE_FAILED } from '@/lib/look/errors';
import { createLookForm, editLookForm, lookName, unchangedLookRequest } from '@/lib/look/form';
import {
  LOOK_LIMITS,
  LOOK_ORDER_PATH,
  LOOKS_PATH,
  lookPath,
  type LookAdmin,
  type LookList,
} from '@/lib/look/types';
import { LookEditor } from './LookEditor';
import { LookRow } from './LookRow';

type Loaded =
  | { kind: 'ok'; looks: LookAdmin[] }
  | { kind: 'failed' }
  | { kind: 'denied'; denied: AccessDenied };

type Editing = { mode: 'create' } | { mode: 'edit'; lookId: string };

/** 반영 안내 — 고객 조회는 공유 캐시(60초)라 늦게 보일 수 있다(§2-11) */
const REFLECT = '상품 상세에는 1분 안에 반영됩니다.';

export const LOOK_DRAG_INSTRUCTIONS =
  '스페이스 또는 엔터로 룩을 집고, 위 · 아래 화살표로 옮긴 뒤 스페이스 또는 엔터로 놓습니다. Esc 로 취소합니다.';

/**
 * 어드민 룩(Shop the Look) 관리(#406) — 대표 이미지 + 구성 상품 묶음의 목록 · 만들기 · 고치기 · 삭제 · 순서 · 노출.
 * 메인 전시 관리(`DisplayManager`)와 같은 규칙이다:
 * - 순서는 화면에서 바꾼 뒤 "순서 저장" 으로 한 번에 보낸다(`PATCH .../order`, 룩 아이디 전체). 바꾼 순서가 있는 동안에는 다른 일을 막는다.
 * - 노출 켜기 · 끄기 · 수정은 룩 하나씩 전체 교체(`PUT`)다. 삭제는 한 번 더 확인한다(구성 상품도 함께 지워진다).
 * - 서버에 버전이 없어 마지막 쓰기가 이긴다. 그래서 만들기 · 수정 · 삭제 뒤에는 목록을 다시 읽는다.
 * - 한 번에 한 가지만 한다 — 폼이 열려 있거나 요청 중이면 다른 줄의 버튼을 막는다.
 */
export function LookManager() {
  const dndId = useId();
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [order, setOrder] = useState<string[] | null>(null);
  const [savingOrder, setSavingOrder] = useState(false);
  const [orderError, setOrderError] = useState<string | null>(null);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [rowBusy, setRowBusy] = useState<{ lookId: string; action: 'toggle' | 'delete' } | null>(
    null,
  );
  const [rowError, setRowError] = useState<{ lookId: string; message: string } | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const returnFocus = useRef<string | null>(null);

  // 상태는 응답 콜백에서만 바꾼다. 다시 읽는 동안에는 이전 목록을 그대로 보인다
  useEffect(() => {
    let alive = true;
    apiFetch<LookList>(LOOKS_PATH).then(
      (data) => {
        if (!alive) return;
        setLoaded({ kind: 'ok', looks: data.looks });
        setOrder(null);
      },
      (cause: unknown) => {
        if (!alive) return;
        const denied = accessDeniedOf(cause);
        setLoaded(denied ? { kind: 'denied', denied } : { kind: 'failed' });
      },
    );
    return () => {
      alive = false;
    };
  }, [reloadKey]);

  const reload = (message: string | null) => {
    setNotice(message);
    setOrderError(null);
    setReloadKey((n) => n + 1);
  };

  // 옮길 포커스 — 다음 커밋 뒤 `data-focus` 가 같은 버튼으로, 없으면 제목으로 옮긴다. 한 번 쓰고 비운다
  const pendingFocus = useRef<{ keys: string[] } | null>(null);
  useEffect(() => {
    const pending = pendingFocus.current;
    if (!pending) return;
    pendingFocus.current = null;
    const target = pending.keys
      .map((key) => document.querySelector<HTMLElement>(`[data-focus="${CSS.escape(key)}"]`))
      .find((element) => element && !(element as HTMLButtonElement).disabled);
    (target ?? headingRef.current)?.focus();
  });
  const focusLater = (...keys: string[]) => {
    pendingFocus.current = { keys };
  };

  const saved = loaded?.kind === 'ok' ? loaded.looks : [];
  const byId = new Map(saved.map((look) => [look.lookId, look]));
  const savedIds = saved.map((look) => look.lookId);
  const ids = order ?? savedIds;
  const looks = ids
    .map((id) => byId.get(id))
    .filter((look): look is LookAdmin => look !== undefined);
  const orderDirty = order !== null && !sameOrder(order, savedIds);

  const busy = editing !== null || rowBusy !== null || savingOrder;
  const actionsLocked = busy || orderDirty;
  const atLimit = saved.length >= LOOK_LIMITS.looks;

  const move = (from: number, to: number) => {
    if (busy) return;
    setOrder(moveItem(ids, from, to));
    setOrderError(null);
    setNotice(null);
  };

  const saveOrder = async () => {
    if (savingOrder || !orderDirty || !order) return;
    setSavingOrder(true);
    setOrderError(null);
    try {
      const data = await apiFetch<LookList>(LOOK_ORDER_PATH, {
        method: 'PATCH',
        json: { lookIds: [...order] },
      });
      setLoaded({ kind: 'ok', looks: data.looks });
      setOrder(null);
      setNotice(`순서를 저장했습니다. ${REFLECT}`);
    } catch (cause) {
      const failure = lookOrderFailure(cause);
      if (failure.kind === 'access') setLoaded({ kind: 'denied', denied: failure.denied });
      else if (failure.kind === 'reload') reload(failure.notice);
      else setOrderError(failure.message);
    } finally {
      setSavingOrder(false);
    }
  };

  const toggle = async (look: LookAdmin) => {
    if (actionsLocked) return;
    setRowBusy({ lookId: look.lookId, action: 'toggle' });
    setRowError(null);
    setNotice(null);
    const visible = !look.visible;
    try {
      const updated = await apiFetch<LookAdmin>(lookPath(look.lookId), {
        method: 'PUT',
        json: unchangedLookRequest(look, visible),
      });
      setLoaded((current) =>
        current?.kind === 'ok'
          ? {
              kind: 'ok',
              looks: current.looks.map((item) => (item.lookId === updated.lookId ? updated : item)),
            }
          : current,
      );
      setNotice(
        `‘${lookName(updated)}’ 을(를) ${visible ? '노출' : '숨김'}으로 바꿨습니다. ${REFLECT}`,
      );
    } catch (cause) {
      const failure = lookRowFailure(cause, TOGGLE_FAILED);
      if (failure.kind === 'access') setLoaded({ kind: 'denied', denied: failure.denied });
      else if (failure.kind === 'reload') reload(failure.notice);
      else setRowError({ lookId: look.lookId, message: failure.message });
    } finally {
      setRowBusy(null);
    }
  };

  const remove = async (look: LookAdmin) => {
    if (rowBusy) return;
    setRowBusy({ lookId: look.lookId, action: 'delete' });
    setRowError(null);
    setNotice(null);
    try {
      await apiFetch<null>(lookPath(look.lookId), { method: 'DELETE' });
      setConfirming(null);
      reload(`‘${lookName(look)}’ 룩을 삭제했습니다. ${REFLECT}`);
      focusLater();
    } catch (cause) {
      const failure = lookRowFailure(cause, DELETE_FAILED);
      if (failure.kind === 'access') setLoaded({ kind: 'denied', denied: failure.denied });
      else if (failure.kind === 'reload') {
        setConfirming(null);
        reload(failure.notice);
        focusLater();
      } else setRowError({ lookId: look.lookId, message: failure.message });
    } finally {
      setRowBusy(null);
    }
  };

  const open = (next: Editing, focusKey: string) => {
    returnFocus.current = focusKey;
    setEditing(next);
    setConfirming(null);
    setRowError(null);
    setNotice(null);
  };

  const closeEditor = (message: string | null, reloadList: boolean) => {
    setEditing(null);
    if (reloadList) reload(message);
    focusLater(...(returnFocus.current ? [returnFocus.current] : []));
  };

  const editor = editing && loaded?.kind === 'ok' ? editorProps(editing, byId) : null;

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );
  const nameOf = (id: UniqueIdentifier) => {
    const look = byId.get(String(id));
    return look ? lookName(look) : '';
  };
  const positionOf = (id: UniqueIdentifier) => ids.indexOf(String(id)) + 1;
  const announcements: Announcements = {
    onDragStart: ({ active }) => `${nameOf(active.id)} 룩을 집었습니다.`,
    onDragOver: ({ active, over }) =>
      over
        ? `${nameOf(active.id)} 룩을 ${positionOf(over.id)}번째 자리로 옮기는 중입니다.`
        : undefined,
    onDragEnd: ({ active, over }) =>
      over
        ? `${nameOf(active.id)} 룩을 ${positionOf(over.id)}번째 자리에 놓았습니다. 순서 저장을 눌러야 반영됩니다.`
        : undefined,
    onDragCancel: ({ active }) => `${nameOf(active.id)} 룩 옮기기를 취소했습니다.`,
  };
  const onDragEnd = ({ active, over }: DragEndEvent) => {
    if (!over || active.id === over.id) return;
    move(ids.indexOf(String(active.id)), ids.indexOf(String(over.id)));
  };

  return (
    <div className="flex max-w-4xl min-w-0 flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 ref={headingRef} tabIndex={-1} className="text-xl font-semibold outline-none">
            룩
          </h1>
          <p className="mt-1 text-sm text-muted-fg">
            대표 이미지 한 장과 함께 입은 상품을 묶어 상품 상세의 SHOP THE LOOK 에 보입니다. 룩에
            넣은 상품의 상세 화면에 그 룩이 이 순서대로 보입니다. 저장한 내용은 1분 안에 반영됩니다.
          </p>
        </div>
        {loaded?.kind === 'ok' && (
          <div className="flex flex-wrap gap-2">
            <Button
              data-focus="create"
              disabled={actionsLocked || atLimit}
              onClick={() => open({ mode: 'create' }, 'create')}
            >
              룩 추가
            </Button>
            <Button variant="secondary" disabled={busy} onClick={() => reload(null)}>
              다시 불러오기
            </Button>
          </div>
        )}
      </div>
      {loaded?.kind === 'ok' && atLimit && (
        <p className="text-sm text-muted-fg">
          룩은 최대 {LOOK_LIMITS.looks}개까지 만들 수 있습니다. 쓰지 않는 룩을 지우면 새로 만들 수
          있습니다.
        </p>
      )}

      {/* 결과 안내는 늘 있는 live region 에 쓴다(비어도 렌더 트리에 남긴다) */}
      <p role="status" className="min-h-5 text-sm">
        {notice}
      </p>

      {loaded === null && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {loaded?.kind === 'denied' && <AccessNotice denied={loaded.denied} />}
      {loaded?.kind === 'failed' && (
        <div role="alert" className="flex items-center gap-4">
          <p className="text-sm text-danger">{LOAD_FAILED}</p>
          <Button variant="secondary" onClick={() => reload(null)}>
            다시 불러오기
          </Button>
        </div>
      )}

      {editor && (
        <LookEditor
          key={editor.key}
          heading={editor.heading}
          lookId={editor.lookId}
          initial={editor.initial}
          onSaved={(title) => closeEditor(`‘${title}’ 을(를) 저장했습니다. ${REFLECT}`, true)}
          onReload={(message) => closeEditor(message, true)}
          onDenied={(denied) => setLoaded({ kind: 'denied', denied })}
          onCancel={() => closeEditor(null, false)}
        />
      )}

      {loaded?.kind === 'ok' && looks.length === 0 && (
        <p className="text-sm text-muted-fg">
          아직 룩이 없습니다. “룩 추가” 로 첫 룩을 만들어 보세요.
        </p>
      )}
      {loaded?.kind === 'ok' && looks.length > 0 && (
        <div className="flex flex-col gap-3">
          <p className="text-xs text-muted-fg">
            손잡이를 끌거나 위 · 아래 버튼으로 순서를 바꾼 뒤 “순서 저장” 을 누르세요. 키보드로는{' '}
            {LOOK_DRAG_INSTRUCTIONS}
          </p>
          {(orderDirty || orderError) && (
            <div className="flex flex-wrap items-center gap-3 rounded-md bg-muted px-4 py-3">
              <p className="flex-1 text-sm">
                순서를 바꿨습니다. 저장해야 반영됩니다. 저장하거나 되돌리기 전에는 다른 일을 할 수
                없습니다.
              </p>
              <Button disabled={savingOrder || !orderDirty} onClick={() => void saveOrder()}>
                {savingOrder ? '저장 중…' : '순서 저장'}
              </Button>
              <Button
                variant="secondary"
                disabled={savingOrder}
                onClick={() => {
                  setOrder(null);
                  setOrderError(null);
                }}
              >
                되돌리기
              </Button>
              {orderError && (
                <p role="alert" className="w-full text-sm text-danger">
                  {orderError}
                </p>
              )}
            </div>
          )}
          <DndContext
            id={dndId}
            sensors={sensors}
            collisionDetection={closestCenter}
            onDragEnd={onDragEnd}
            accessibility={{
              announcements,
              screenReaderInstructions: { draggable: LOOK_DRAG_INSTRUCTIONS },
            }}
          >
            <SortableContext items={ids} strategy={verticalListSortingStrategy}>
              <ul className="flex flex-col gap-1" aria-label="룩 목록 (위가 먼저 보입니다)">
                {looks.map((look, index) => {
                  const id = look.lookId;
                  return (
                    <LookRow
                      key={id}
                      look={look}
                      position={index + 1}
                      total={looks.length}
                      reorderLocked={busy}
                      actionsLocked={actionsLocked}
                      toggling={rowBusy?.lookId === id && rowBusy.action === 'toggle'}
                      confirming={confirming === id}
                      deleting={rowBusy?.lookId === id && rowBusy.action === 'delete'}
                      error={rowError?.lookId === id ? rowError.message : null}
                      onMove={(to) => {
                        move(index, to);
                        focusLater(
                          to < index ? `up:${id}` : `down:${id}`,
                          to < index ? `down:${id}` : `up:${id}`,
                        );
                      }}
                      onToggle={() => void toggle(look)}
                      onEdit={() => open({ mode: 'edit', lookId: id }, `edit:${id}`)}
                      onAskDelete={() => {
                        setConfirming(id);
                        setRowError(null);
                        focusLater(`confirm-cancel:${id}`);
                      }}
                      onCancelDelete={() => {
                        if (rowBusy) return;
                        setConfirming(null);
                        setRowError(null);
                        focusLater(`delete:${id}`);
                      }}
                      onDelete={() => void remove(look)}
                    />
                  );
                })}
              </ul>
            </SortableContext>
          </DndContext>
        </div>
      )}
    </div>
  );
}

/** 편집 대상 → 폼 속성. 고칠 룩이 다시 읽은 목록에 없으면(다른 곳에서 지움) 폼을 닫는다 */
function editorProps(editing: Editing, byId: Map<string, LookAdmin>) {
  if (editing.mode === 'create') {
    return { key: 'create', heading: '룩 추가', lookId: null, initial: createLookForm() };
  }
  const look = byId.get(editing.lookId);
  if (!look) return null;
  return {
    key: `edit:${look.lookId}`,
    heading: `‘${lookName(look)}’ 수정`,
    lookId: look.lookId,
    initial: editLookForm(look),
  };
}
