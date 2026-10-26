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
import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import { DELETE_FAILED, orderFailure, rowFailure, TOGGLE_FAILED } from '@/lib/display/errors';
import { createForm, editForm, unchangedRequest, type SectionForm } from '@/lib/display/form';
import { moveItem, orderRequest, sameOrder } from '@/lib/display/order';
import { previewSections } from '@/lib/display/preview';
import {
  DRAG_INSTRUCTIONS,
  dragAnnouncements,
  editableType,
  sectionName,
} from '@/lib/display/section-view';
import {
  CREATABLE_TYPES,
  DISPLAY_ORDER_PATH,
  DISPLAY_SECTIONS_PATH,
  LIMITS,
  TYPE_LABELS,
  type DisplaySectionAdmin,
  type DisplaySectionList,
  type SectionTypeCode,
} from '@/lib/display/types';
import { PreviewPanel } from './PreviewPanel';
import { SectionEditor } from './SectionEditor';
import { SectionRow } from './SectionRow';

type Loaded =
  | { kind: 'ok'; sections: DisplaySectionAdmin[] }
  | { kind: 'failed' }
  | { kind: 'denied'; denied: AccessDenied };

type Editing = { mode: 'create'; type: SectionTypeCode } | { mode: 'edit'; sectionId: string };

const sectionPath = (sectionId: string) =>
  `${DISPLAY_SECTIONS_PATH}/${encodeURIComponent(sectionId)}`;

/**
 * 어드민 메인 전시 관리(#71) — 스토어프론트 메인의 섹션 순서 · 노출 · 문구 · 상품을 바꾼다.
 * - 순서는 화면에서 바꾼 뒤 "순서 저장" 으로 한 번에 보낸다(`PATCH .../order`, 섹션 아이디 전체). 바꾼 순서가 있는 동안에는 다른 일을 막는다.
 * - 노출 켜기 · 끄기 · 수정은 섹션 하나씩 전체 교체(`PUT`)다. 삭제는 한 번 더 확인한다.
 * - 서버에 버전이 없어 마지막 쓰기가 이긴다(BE 계약). 그래서 만들기 · 수정 · 삭제 뒤에는 목록을 다시 읽어 지금 상태를 보인다.
 * - 한 번에 한 가지만 한다 — 폼이 열려 있거나 요청 중이면 다른 줄의 버튼을 막는다(카테고리 화면과 같다).
 * - 미리보기는 저장하지 않은 순서 · 열린 폼까지 스토어프론트 메인으로 그려 본다(`PreviewPanel`). `previewOrigin` 이 없으면 끈다.
 */
export function DisplayManager({ previewOrigin }: { previewOrigin: string | null }) {
  const dndId = useId();
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  // 화면에서 바꾼 순서. null 이면 서버 순서 그대로다
  const [order, setOrder] = useState<string[] | null>(null);
  const [savingOrder, setSavingOrder] = useState(false);
  const [orderError, setOrderError] = useState<string | null>(null);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [rowBusy, setRowBusy] = useState<{ sectionId: string; action: 'toggle' | 'delete' } | null>(
    null,
  );
  const [rowError, setRowError] = useState<{ sectionId: string; message: string } | null>(null);
  const [previewOpen, setPreviewOpen] = useState(false);
  // 열린 폼의 저장 전 값 — 어느 폼의 값인지(key) 함께 둬서 폼을 바꾼 직후 이전 폼 값을 쓰지 않는다
  const [draft, setDraft] = useState<{ key: string; form: SectionForm } | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const returnFocus = useRef<string | null>(null);

  // 상태는 응답 콜백에서만 바꾼다. 다시 읽는 동안에는 이전 목록을 그대로 보인다
  useEffect(() => {
    let alive = true;
    apiFetch<DisplaySectionList>(DISPLAY_SECTIONS_PATH).then(
      (data) => {
        if (!alive) return;
        setLoaded({ kind: 'ok', sections: data.sections });
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

  const saved = loaded?.kind === 'ok' ? loaded.sections : [];
  const byId = new Map(saved.map((section) => [section.sectionId, section]));
  const savedIds = saved.map((section) => section.sectionId);
  const ids = order ?? savedIds;
  const sections = ids
    .map((id) => byId.get(id))
    .filter((section): section is DisplaySectionAdmin => section !== undefined);
  const orderDirty = order !== null && !sameOrder(order, savedIds);

  const busy = editing !== null || rowBusy !== null || savingOrder;
  const reorderLocked = busy;
  const actionsLocked = busy || orderDirty;

  const move = (from: number, to: number) => {
    if (reorderLocked) return;
    setOrder(moveItem(ids, from, to));
    setOrderError(null);
    setNotice(null);
  };

  const saveOrder = async () => {
    if (savingOrder || !orderDirty || !order) return;
    setSavingOrder(true);
    setOrderError(null);
    try {
      const data = await apiFetch<DisplaySectionList>(DISPLAY_ORDER_PATH, {
        method: 'PATCH',
        json: orderRequest(order),
      });
      setLoaded({ kind: 'ok', sections: data.sections });
      setOrder(null);
      setNotice('순서를 저장했습니다. 메인에는 1분 안에 반영됩니다.');
    } catch (cause) {
      const failure = orderFailure(cause);
      if (failure.kind === 'access') setLoaded({ kind: 'denied', denied: failure.denied });
      else if (failure.kind === 'reload') reload(failure.notice);
      else setOrderError(failure.message);
    } finally {
      setSavingOrder(false);
    }
  };

  const toggle = async (section: DisplaySectionAdmin) => {
    if (actionsLocked) return;
    setRowBusy({ sectionId: section.sectionId, action: 'toggle' });
    setRowError(null);
    setNotice(null);
    const visible = !section.visible;
    try {
      const updated = await apiFetch<DisplaySectionAdmin>(sectionPath(section.sectionId), {
        method: 'PUT',
        json: unchangedRequest(section, { visible }),
      });
      setLoaded((current) =>
        current?.kind === 'ok'
          ? {
              kind: 'ok',
              sections: current.sections.map((item) =>
                item.sectionId === updated.sectionId ? updated : item,
              ),
            }
          : current,
      );
      setNotice(
        `‘${sectionName(updated)}’ 을(를) ${visible ? '노출' : '숨김'}으로 바꿨습니다. 지금 상태: ${updated.liveStatus.name}. 메인에는 1분 안에 반영됩니다.`,
      );
    } catch (cause) {
      const failure = rowFailure(cause, TOGGLE_FAILED);
      if (failure.kind === 'access') setLoaded({ kind: 'denied', denied: failure.denied });
      else if (failure.kind === 'reload') reload(failure.notice);
      else setRowError({ sectionId: section.sectionId, message: failure.message });
    } finally {
      setRowBusy(null);
    }
  };

  const remove = async (section: DisplaySectionAdmin) => {
    if (rowBusy) return;
    setRowBusy({ sectionId: section.sectionId, action: 'delete' });
    setRowError(null);
    setNotice(null);
    try {
      await apiFetch<null>(sectionPath(section.sectionId), { method: 'DELETE' });
      setConfirming(null);
      reload(`‘${sectionName(section)}’ 섹션을 삭제했습니다. 메인에는 1분 안에 반영됩니다.`);
      focusLater();
    } catch (cause) {
      const failure = rowFailure(cause, DELETE_FAILED);
      if (failure.kind === 'access') setLoaded({ kind: 'denied', denied: failure.denied });
      else if (failure.kind === 'reload') {
        setConfirming(null);
        reload(failure.notice);
        focusLater();
      } else setRowError({ sectionId: section.sectionId, message: failure.message });
    } finally {
      setRowBusy(null);
    }
  };

  const open = (next: Editing, focusKey: string) => {
    returnFocus.current = focusKey;
    setEditing(next);
    setDraft(null);
    setConfirming(null);
    setRowError(null);
    setNotice(null);
  };

  const closeEditor = (message: string | null, reloadList: boolean) => {
    setEditing(null);
    setDraft(null);
    if (reloadList) reload(message);
    focusLater(...(returnFocus.current ? [returnFocus.current] : []));
  };

  const editor = editing && loaded?.kind === 'ok' ? editorProps(editing, byId) : null;
  const draftForm = editor && draft?.key === editor.key ? draft.form : null;
  const preview = useMemo(() => {
    if (!previewOpen || loaded?.kind !== 'ok') return null;
    const byIdNow = new Map(loaded.sections.map((section) => [section.sectionId, section]));
    const ordered = (order ?? loaded.sections.map((section) => section.sectionId))
      .map((id) => byIdNow.get(id))
      .filter((section): section is DisplaySectionAdmin => section !== undefined);
    const editingNow =
      editing && draftForm
        ? editing.mode === 'edit'
          ? { mode: 'edit' as const, sectionId: editing.sectionId, form: draftForm }
          : { mode: 'create' as const, form: draftForm }
        : null;
    return previewSections(ordered, editingNow);
  }, [previewOpen, loaded, order, editing, draftForm]);
  const atLimit = saved.length >= LIMITS.sections;

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );
  const nameOf = (id: UniqueIdentifier) => {
    const section = byId.get(String(id));
    return section ? sectionName(section) : '';
  };
  const positionOf = (id: UniqueIdentifier) => ids.indexOf(String(id)) + 1;
  const announcements: Announcements = {
    onDragStart: ({ active }) => dragAnnouncements.start(nameOf(active.id)),
    onDragOver: ({ active, over }) =>
      over ? dragAnnouncements.over(nameOf(active.id), positionOf(over.id)) : undefined,
    onDragEnd: ({ active, over }) =>
      over ? dragAnnouncements.end(nameOf(active.id), positionOf(over.id)) : undefined,
    onDragCancel: ({ active }) => dragAnnouncements.cancel(nameOf(active.id)),
  };
  const onDragEnd = ({ active, over }: DragEndEvent) => {
    if (!over || active.id === over.id) return;
    move(ids.indexOf(String(active.id)), ids.indexOf(String(over.id)));
  };

  return (
    <div
      className={
        preview && previewOrigin
          ? 'grid gap-8 xl:grid-cols-[minmax(0,44rem)_minmax(0,1fr)] xl:items-start'
          : undefined
      }
    >
      <div className="flex max-w-4xl min-w-0 flex-col gap-6">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <h1 ref={headingRef} tabIndex={-1} className="text-xl font-semibold outline-none">
              메인 전시
            </h1>
            <p className="mt-1 text-sm text-muted-fg">
              쇼핑몰 메인 화면에 보이는 섹션의 순서 · 노출 · 문구 · 상품을 바꿉니다. 저장한 내용은
              메인에 1분 안에 반영됩니다. 보이는 섹션이 하나도 없으면 메인은 기본 화면을 보입니다.
            </p>
          </div>
          {loaded?.kind === 'ok' && (
            <div className="flex flex-wrap gap-2">
              {CREATABLE_TYPES.map((type) => (
                <Button
                  key={type}
                  data-focus={`create:${type}`}
                  disabled={actionsLocked || atLimit}
                  onClick={() => open({ mode: 'create', type }, `create:${type}`)}
                >
                  {TYPE_LABELS[type]} 추가
                </Button>
              ))}
              <Button variant="secondary" disabled={busy} onClick={() => reload(null)}>
                다시 불러오기
              </Button>
              {previewOrigin && (
                <Button
                  variant="secondary"
                  aria-expanded={previewOpen}
                  aria-controls={previewOpen ? 'display-preview' : undefined}
                  onClick={() => setPreviewOpen((open) => !open)}
                >
                  {previewOpen ? '미리보기 닫기' : '미리보기'}
                </Button>
              )}
            </div>
          )}
        </div>
        {loaded?.kind === 'ok' && atLimit && (
          <p className="text-sm text-muted-fg">
            섹션은 최대 {LIMITS.sections}개까지 만들 수 있습니다. 쓰지 않는 섹션을 지우면 새로 만들
            수 있습니다.
          </p>
        )}
        {loaded?.kind === 'ok' && (
          <p className="text-xs text-muted-fg">
            히어로 · 디테일처럼 이미지 · 영상이 꼭 있어야 하는 섹션은 업로드 기능이 준비되면 만들 수
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
          <SectionEditor
            key={editor.key}
            heading={editor.heading}
            sectionId={editor.sectionId}
            initial={editor.initial}
            onSaved={(name) =>
              closeEditor(`‘${name}’ 을(를) 저장했습니다. 메인에는 1분 안에 반영됩니다.`, true)
            }
            onReload={(message) => closeEditor(message, true)}
            onDenied={(denied) => setLoaded({ kind: 'denied', denied })}
            onCancel={() => closeEditor(null, false)}
            onFormChange={(form) => setDraft({ key: editor.key, form })}
          />
        )}

        {loaded?.kind === 'ok' && sections.length === 0 && (
          <p className="text-sm text-muted-fg">
            아직 섹션이 없습니다. 지금 메인은 기본 화면을 보입니다.
          </p>
        )}
        {loaded?.kind === 'ok' && sections.length > 0 && (
          <div className="flex flex-col gap-3">
            <p className="text-xs text-muted-fg">
              손잡이를 끌거나 위 · 아래 버튼으로 순서를 바꾼 뒤 “순서 저장” 을 누르세요. 키보드로는{' '}
              {DRAG_INSTRUCTIONS}
            </p>
            {(orderDirty || orderError) && (
              <div className="flex flex-wrap items-center gap-3 rounded-md bg-muted px-4 py-3">
                <p className="flex-1 text-sm">
                  순서를 바꿨습니다. 저장해야 메인에 반영됩니다. 저장하거나 되돌리기 전에는 다른
                  일을 할 수 없습니다.
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
                screenReaderInstructions: { draggable: DRAG_INSTRUCTIONS },
              }}
            >
              <SortableContext items={ids} strategy={verticalListSortingStrategy}>
                <ul
                  className="flex flex-col gap-1"
                  aria-label="메인 섹션 목록 (위가 먼저 보입니다)"
                >
                  {sections.map((section, index) => {
                    const id = section.sectionId;
                    return (
                      <SectionRow
                        key={id}
                        section={section}
                        position={index + 1}
                        total={sections.length}
                        reorderLocked={reorderLocked}
                        actionsLocked={actionsLocked}
                        toggling={rowBusy?.sectionId === id && rowBusy.action === 'toggle'}
                        confirming={confirming === id}
                        deleting={rowBusy?.sectionId === id && rowBusy.action === 'delete'}
                        error={rowError?.sectionId === id ? rowError.message : null}
                        onMove={(to) => {
                          move(index, to);
                          // 옮긴 줄의 같은 버튼으로 — 끝에 닿아 막혔으면 반대 버튼으로
                          focusLater(
                            to < index ? `up:${id}` : `down:${id}`,
                            to < index ? `down:${id}` : `up:${id}`,
                          );
                        }}
                        onToggle={() => void toggle(section)}
                        onEdit={() => {
                          const type = editableType(section);
                          if (type) open({ mode: 'edit', sectionId: id }, `edit:${id}`);
                        }}
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
                        onDelete={() => void remove(section)}
                      />
                    );
                  })}
                </ul>
              </SortableContext>
            </DndContext>
          </div>
        )}
      </div>
      {preview && previewOrigin && (
        <div id="display-preview" className="min-w-0 xl:sticky xl:top-6">
          <PreviewPanel
            origin={previewOrigin}
            sections={preview}
            onClose={() => setPreviewOpen(false)}
          />
        </div>
      )}
    </div>
  );
}

/** 편집 대상 → 폼 속성. 고칠 섹션이 다시 읽은 목록에 없으면(다른 곳에서 지움) 폼을 닫는다 */
function editorProps(editing: Editing, byId: Map<string, DisplaySectionAdmin>) {
  if (editing.mode === 'create') {
    return {
      key: `create:${editing.type}`,
      heading: `${TYPE_LABELS[editing.type]} 섹션 추가`,
      sectionId: null,
      initial: createForm(editing.type),
    };
  }
  const section = byId.get(editing.sectionId);
  const type = section ? editableType(section) : null;
  if (!section || !type) return null;
  return {
    key: `edit:${section.sectionId}`,
    heading: `‘${sectionName(section)}’ 수정`,
    sectionId: section.sectionId,
    initial: editForm(section, type),
  };
}
