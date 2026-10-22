'use client';

import { apiFetch } from '@cf/api-client';
import { Button, cx } from '@cf/ui';
import { useEffect, useRef, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import { categoryDeleteFailure, createForm, editForm } from '@/lib/category/form';
import {
  findNode,
  flattenTree,
  nextDisplayOrder,
  parentCandidates,
  type CategoryRow,
} from '@/lib/category/tree';
import {
  CATEGORIES_PATH,
  MAX_DEPTH,
  type CategoryNode,
  type CategoryTree,
} from '@/lib/category/types';
import { CategoryEditor } from './CategoryEditor';

type Loaded =
  | { kind: 'ok'; roots: CategoryNode[] }
  | { kind: 'failed' }
  | { kind: 'denied'; denied: AccessDenied };

type Editing = { mode: 'create'; parentId: string | null } | { mode: 'edit'; categoryId: string };

/** 깊이별 들여쓰기 — 대 · 중 · 소 */
const INDENT = ['', 'pl-6', 'pl-12'];

const linkButton = 'text-sm underline-offset-4 hover:underline disabled:opacity-50';

/**
 * 어드민 카테고리 관리(#206) — 숨김 포함 트리를 보고, 만들고, 고치고(상위 이동 포함), 지운다.
 * 서버에 version 이 없어 마지막 쓰기가 이긴다. 그래서 쓰기마다 트리를 다시 읽어 지금 상태를 보인다.
 * 화면은 순서 · 깊이를 계산해 정하지 않는다 — 고르기 쉽게 거를 뿐, 정본은 서버 검증이다.
 * 한 번에 한 가지만 한다 — 폼이 열려 있거나 삭제 중이면 다른 줄의 버튼을 막는다. 그래서 폼이 열린 동안에는 트리를 다시 읽지 않고
 * (후보 · 대상이 바뀌지 않는다), 삭제 401 로 로그인 화면에 갈 때 잃을 입력도 없다.
 */
export function CategoryManager() {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);
  const [rowError, setRowError] = useState<{ categoryId: string; message: string } | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  // 폼을 닫으면 연 버튼으로 포커스를 돌려준다(그 줄이 없어졌으면 제목으로)
  const returnFocus = useRef<string | null>(null);

  // 상태는 응답 콜백에서만 바꾼다. 다시 읽는 동안에는 이전 트리를 그대로 보인다
  useEffect(() => {
    let alive = true;
    apiFetch<CategoryTree>(CATEGORIES_PATH).then(
      (data) => alive && setLoaded({ kind: 'ok', roots: data.categories }),
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
    setReloadKey((n) => n + 1);
  };

  // 옮길 포커스 — 다음 커밋 뒤(아래 effect) `data-focus` 가 같은 버튼으로, 없으면 제목으로 옮긴다. 한 번 쓰고 비운다
  const pendingFocus = useRef<{ key: string | null } | null>(null);
  useEffect(() => {
    const pending = pendingFocus.current;
    if (!pending) return;
    pendingFocus.current = null;
    const target = pending.key
      ? document.querySelector<HTMLElement>(`[data-focus="${CSS.escape(pending.key)}"]`)
      : null;
    (target ?? headingRef.current)?.focus();
  });
  const focusLater = (key: string | null) => {
    pendingFocus.current = { key };
  };

  const open = (next: Editing, focusKey: string) => {
    returnFocus.current = focusKey;
    setEditing(next);
    setConfirming(null);
    setRowError(null);
    setNotice(null);
  };

  const remove = async (row: CategoryRow) => {
    if (deleting) return;
    setDeleting(row.node.categoryId);
    setRowError(null);
    setNotice(null);
    try {
      await apiFetch<null>(`${CATEGORIES_PATH}/${encodeURIComponent(row.node.categoryId)}`, {
        method: 'DELETE',
      });
      setConfirming(null);
      reload(`‘${row.node.name}’ 을(를) 삭제했습니다.`);
      focusLater(null);
    } catch (cause) {
      const failure = categoryDeleteFailure(cause);
      if (failure.kind === 'access') setLoaded({ kind: 'denied', denied: failure.denied });
      else if (failure.kind === 'reload') {
        setConfirming(null);
        reload(failure.notice);
        focusLater(null);
      } else setRowError({ categoryId: row.node.categoryId, message: failure.message });
    } finally {
      setDeleting(null);
    }
  };

  const roots = loaded?.kind === 'ok' ? loaded.roots : [];
  const rows = flattenTree(roots);
  const editor = editing && loaded?.kind === 'ok' ? editorProps(editing, roots, rows) : null;
  // 폼이 열려 있거나 삭제 중이면 다른 동작을 막는다(한 번에 한 가지)
  const busy = editing !== null || deleting !== null;

  const closeEditor = (message: string | null, reloadTree: boolean) => {
    setEditing(null);
    if (reloadTree) reload(message);
    focusLater(returnFocus.current);
  };

  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 ref={headingRef} tabIndex={-1} className="text-xl font-semibold outline-none">
            카테고리
          </h1>
          <p className="mt-1 text-sm text-muted-fg">
            대 · 중 · 소 {MAX_DEPTH}단계까지 만들 수 있습니다. 숨긴 카테고리는 고객 메뉴에서
            빠집니다.
          </p>
        </div>
        {loaded?.kind === 'ok' && (
          <Button
            data-focus="create-root"
            disabled={busy}
            onClick={() => open({ mode: 'create', parentId: null }, 'create-root')}
          >
            최상위 추가
          </Button>
        )}
      </div>

      {/* 결과 안내는 늘 있는 live region 에 쓴다(비어도 렌더 트리에 남긴다) — 안내가 새로 생길 때도 읽힌다 */}
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
        <CategoryEditor
          key={editor.key}
          title={editor.title}
          categoryId={editor.categoryId}
          initial={editor.initial}
          candidates={editor.candidates}
          onSaved={(saved) => closeEditor(`‘${saved.name}’ 을(를) 저장했습니다.`, true)}
          onReload={(message) => closeEditor(message, true)}
          onDenied={(denied) => setLoaded({ kind: 'denied', denied })}
          onCancel={() => closeEditor(null, false)}
        />
      )}

      {loaded?.kind === 'ok' && rows.length === 0 && (
        <p className="text-sm text-muted-fg">
          아직 카테고리가 없습니다. 최상위 카테고리부터 추가하세요.
        </p>
      )}
      {loaded?.kind === 'ok' && rows.length > 0 && (
        <ul className="flex flex-col gap-1" aria-label="카테고리 목록">
          {rows.map((row) => {
            const { node } = row;
            const isConfirming = confirming === node.categoryId;
            const error = rowError?.categoryId === node.categoryId ? rowError.message : null;
            return (
              <li key={node.categoryId} className={INDENT[row.depth - 1]}>
                <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 rounded-md bg-muted/50 px-4 py-3">
                  <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-sm">
                    <span className={cx('font-medium', row.hiddenBy && 'text-muted-fg')}>
                      {node.name}
                    </span>
                    <span className="text-xs text-muted-fg">순서 {node.displayOrder}</span>
                    {row.hiddenBy === 'self' && (
                      <span className="rounded bg-muted px-1.5 py-0.5 text-xs">숨김</span>
                    )}
                    {row.hiddenBy === 'ancestor' && (
                      <span className="rounded bg-muted px-1.5 py-0.5 text-xs">상위가 숨김</span>
                    )}
                  </div>
                  {isConfirming ? (
                    <div className="flex items-center gap-3">
                      <span className="text-sm">삭제할까요?</span>
                      {/* 삭제 중에도 disabled 로 두지 않는다 — 포커스가 body 로 빠진다. 겹친 요청은 remove 가 막는다 */}
                      <button
                        type="button"
                        className={cx(linkButton, 'font-medium text-danger')}
                        aria-disabled={deleting !== null || undefined}
                        onClick={() => void remove(row)}
                      >
                        {deleting === node.categoryId ? '삭제 중…' : '삭제'}
                      </button>
                      {/* 누른 삭제 버튼이 이 확인으로 바뀌어 사라진다 — 포커스를 안전한 쪽(취소)에 둔다 */}
                      <button
                        type="button"
                        className={linkButton}
                        aria-disabled={deleting !== null || undefined}
                        data-focus={`confirm-cancel:${node.categoryId}`}
                        onClick={() => {
                          if (deleting) return;
                          setConfirming(null);
                          setRowError(null);
                          focusLater(`delete:${node.categoryId}`);
                        }}
                      >
                        취소
                      </button>
                    </div>
                  ) : (
                    <div className="flex items-center gap-3">
                      <button
                        type="button"
                        className={linkButton}
                        aria-label={`${node.name} 수정`}
                        data-focus={`edit:${node.categoryId}`}
                        disabled={busy}
                        onClick={() =>
                          open(
                            { mode: 'edit', categoryId: node.categoryId },
                            `edit:${node.categoryId}`,
                          )
                        }
                      >
                        수정
                      </button>
                      {row.depth < MAX_DEPTH && (
                        <button
                          type="button"
                          className={linkButton}
                          aria-label={`${node.name} 하위 추가`}
                          data-focus={`child:${node.categoryId}`}
                          disabled={busy}
                          onClick={() =>
                            open(
                              { mode: 'create', parentId: node.categoryId },
                              `child:${node.categoryId}`,
                            )
                          }
                        >
                          하위 추가
                        </button>
                      )}
                      <button
                        type="button"
                        className={cx(linkButton, 'text-danger')}
                        aria-label={`${node.name} 삭제`}
                        data-focus={`delete:${node.categoryId}`}
                        disabled={busy}
                        onClick={() => {
                          setConfirming(node.categoryId);
                          setRowError(null);
                          focusLater(`confirm-cancel:${node.categoryId}`);
                        }}
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
          })}
        </ul>
      )}
    </div>
  );
}

/** 편집 대상 → 폼 속성. 고칠 카테고리가 다시 읽은 트리에 없으면(다른 곳에서 지움) 폼을 닫는다 */
function editorProps(editing: Editing, roots: CategoryNode[], rows: CategoryRow[]) {
  if (editing.mode === 'create') {
    const parent = editing.parentId === null ? null : findNode(roots, editing.parentId);
    if (editing.parentId !== null && !parent) return null;
    return {
      key: `create:${editing.parentId ?? ''}`,
      title: parent ? `‘${parent.name}’ 에 하위 카테고리 추가` : '최상위 카테고리 추가',
      categoryId: null,
      initial: createForm(editing.parentId, nextDisplayOrder(parent ? parent.children : roots)),
      candidates: parentCandidates(rows, null),
    };
  }
  const node = findNode(roots, editing.categoryId);
  if (!node) return null;
  return {
    key: `edit:${node.categoryId}`,
    title: `‘${node.name}’ 수정`,
    categoryId: node.categoryId,
    initial: editForm(node),
    candidates: parentCandidates(rows, node),
  };
}
