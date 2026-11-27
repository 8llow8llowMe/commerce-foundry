'use client';

import { ApiError, apiFetch } from '@cf/api-client';
import { Button, cx, FieldError } from '@cf/ui';
import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied, type FormErrors } from '@/lib/api-errors';
import { flattenTree } from '@/lib/category/tree';
import { CATEGORIES_PATH, type CategoryTree } from '@/lib/category/types';
import {
  CHECK_FIELDS,
  PUBLISH_RELOAD_FAILED,
  publishNeedsReload,
  publishOutcome,
  writeFailure,
} from '@/lib/store-navigation/errors';
import {
  changeCount,
  clearedErrors,
  contentOf,
  draftErrors,
  formOf,
  isDirty,
  nodeName,
  publicStateText,
  publishState,
} from '@/lib/store-navigation/form';
import {
  addNode,
  countAll,
  findRow,
  flatten,
  focusAfterRemove,
  moveBy,
  MOVE_PROBLEMS,
  placementText,
  relocate,
  removeNode,
  updateNode,
  type MenuNode,
  type MoveDirection,
  type MoveResult,
  type MoveTarget,
} from '@/lib/store-navigation/tree';
import {
  LIMITS,
  NOT_CONFIGURED,
  STORE_NAVIGATION_PATH,
  STORE_NAVIGATION_PUBLISH_PATH,
  type NavDetail,
  type NavDraftRequest,
  type NavItemPayload,
  type NavPublishRequest,
} from '@/lib/store-navigation/types';
import {
  ItemDetail,
  leavesScreen,
  MenuPreview,
  PublishConfirm,
  type CategoryChoices,
  type PreviewDevice,
  type PreviewLocale,
} from './StoreNavigationParts';
import { StoreNavigationTree } from './StoreNavigationTree';

type Load =
  | { kind: 'loading' }
  | { kind: 'denied'; denied: AccessDenied }
  | { kind: 'loadFailed' }
  /** base — 서버의 최신 상세(아직 없으면 null). 입력(tree)은 따로 들고 다시 읽어도 지우지 않는다 */
  | { kind: 'ready'; base: NavDetail | null };

type Notice = { tone: 'info' | 'error'; text: string };

/** 상세 읽기. 미등록 404 는 null 이다(빈 편집기로 시작) */
async function loadDetail(): Promise<NavDetail | null> {
  try {
    return await apiFetch<NavDetail>(STORE_NAVIGATION_PATH);
  } catch (cause) {
    if (cause instanceof ApiError && cause.code === NOT_CONFIGURED) return null;
    throw cause;
  }
}

/** 카테고리 고르기 목록. 실패해도 화면은 돈다 — 번호 입력으로 바꾼다 */
async function loadCategories(): Promise<CategoryChoices> {
  try {
    const tree = await apiFetch<CategoryTree>(CATEGORIES_PATH);
    return {
      kind: 'ready',
      options: flattenTree(tree.categories).map((row) => ({
        id: row.node.categoryId,
        label: row.path.join(' > '),
        hidden: row.hiddenBy !== null,
      })),
    };
  } catch {
    return { kind: 'failed' };
  }
}

const toggleButton = (active: boolean) =>
  cx(
    'rounded-md border px-3 py-1 text-sm',
    active ? 'border-primary bg-primary text-primary-fg' : 'border-border bg-bg text-fg',
  );

const DIRECTIONS = ['up', 'down', 'indent', 'outdent'] as const;

export const UNSAVED_LEAVE = '저장하지 않은 메뉴 변경이 사라집니다. 이 화면을 떠날까요?';

/** 옮긴 뒤 포커스 — 대상이 없거나 꺼져 있으면(예: 맨 위로 옮겨 '위로' 가 꺼짐) fallback(같은 항목 손잡이)으로 */
type FocusTarget = { target: string; fallback?: string };
const ADD_ROOT = '[data-add-root]';
const TITLE = '[data-nav-title]';

/**
 * 쇼핑몰 설정 > 메뉴(#602 · BE #599). 스토어프론트 헤더 메뉴(PC 1차 메뉴 · 모바일 바로가기 · 전체 메뉴)를 국문 · 영문 함께 편집한다.
 * - 왼쪽 트리에서 항목을 고르고 오른쪽 상세에서 고친다. 끌기 · `Alt+화살표` · 위치 버튼으로 순서와 단계를 바꾼다(3단계 · 형제 20 · 전체 60).
 * - 초안 저장(`PUT`)은 새 리비전을 더할 뿐 스토어프론트를 바꾸지 않는다. 게시(`POST /publication`)만 공개한다.
 * - 되돌리기는 "저장하지 않은 변경 버리기"(마지막 저장본으로)다. 게시본으로 되돌리기는 리비전 이력 API 가 없어 없다.
 * - 409 · 결과 모름은 자동으로 다시 보내지 않는다(초안 저장은 멱등이 아니다). 저장 실패는 입력을 지키고 최신 상세를 다시 읽고,
 *   게시 실패는 다시 읽어 공개됐으면 성공, 아니면 화면을 최신 저장본으로 바꾼다(`publishOutcome`).
 * 푸터 편집기(`StoreFooterEditor`)와 같은 상태 줄 · 저장 · 미리보기 · 게시 순서와 문구를 쓴다.
 */
export function StoreNavigationEditor() {
  const id = useId();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [tree, setTree] = useState<MenuNode[]>([]);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [errors, setErrors] = useState<FormErrors>({});
  const [notice, setNotice] = useState<Notice | null>(null);
  const [live, setLive] = useState('');
  const [conflict, setConflict] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [discarding, setDiscarding] = useState(false);
  const [pending, setPending] = useState<'save' | 'publish' | null>(null);
  const [device, setDevice] = useState<PreviewDevice>('pc');
  const [locale, setLocale] = useState<PreviewLocale>('ko');
  const [categories, setCategories] = useState<CategoryChoices>({ kind: 'loading' });
  const sending = useRef(false);
  const focusTarget = useRef<FocusTarget | null>(null);

  const start = useCallback(() => {
    loadDetail().then(
      (base) => {
        setLoad({ kind: 'ready', base });
        setTree(formOf(base));
      },
      (cause: unknown) => {
        const denied = accessDeniedOf(cause);
        setLoad(denied ? { kind: 'denied', denied } : { kind: 'loadFailed' });
      },
    );
  }, []);

  useEffect(() => {
    start();
    void loadCategories().then(setCategories);
  }, [start]);

  const base = load.kind === 'ready' ? load.base : null;
  const dirty = load.kind === 'ready' && isDirty(tree, base);

  // 저장하지 않은 변경이 있으면 떠나기 전에 묻는다 — 창 닫기 · 새로 고침은 beforeunload,
  // 셸 메뉴 · 도움말 링크 같은 앱 안 이동은 문서 클릭(캡처)에서 묻고 취소하면 막는다(next/link 는 defaultPrevented 면 이동하지 않는다).
  // 브라우저 뒤로 가기는 막지 못한다(docs/architecture.md #602)
  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    const guard = (event: MouseEvent) => {
      if (event.defaultPrevented || event.button !== 0 || !(event.target instanceof Element))
        return;
      const anchor = event.target.closest('a[href]');
      if (!(anchor instanceof HTMLAnchorElement)) return;
      const modified = event.metaKey || event.ctrlKey || event.shiftKey || event.altKey;
      if (!leavesScreen(anchor, window.location, modified)) return;
      if (!window.confirm(UNSAVED_LEAVE)) event.preventDefault();
    };
    window.addEventListener('beforeunload', warn);
    document.addEventListener('click', guard, true);
    return () => {
      window.removeEventListener('beforeunload', warn);
      document.removeEventListener('click', guard, true);
    };
  }, [dirty]);

  // 옮기거나 더한 뒤 포커스를 따라 보낸다(손잡이 · 위치 버튼 · 새 항목의 이름 버튼)
  useEffect(() => {
    const wanted = focusTarget.current;
    if (!wanted) return;
    focusTarget.current = null;
    for (const selector of [wanted.target, wanted.fallback]) {
      if (!selector) continue;
      const element = document.querySelector<HTMLElement>(selector);
      if (element && !(element instanceof HTMLButtonElement && element.disabled)) {
        element.focus();
        return;
      }
    }
  });

  if (load.kind === 'loading') return <p className="text-sm text-muted-fg">불러오는 중…</p>;
  if (load.kind === 'denied') return <AccessNotice denied={load.denied} />;
  if (load.kind === 'loadFailed')
    return (
      <div role="alert" className="flex items-center gap-4">
        <p className="text-sm text-danger">{LOAD_FAILED}</p>
        <Button
          variant="secondary"
          onClick={() => {
            setLoad({ kind: 'loading' });
            start();
          }}
        >
          다시 불러오기
        </Button>
      </div>
    );

  const busy = pending !== null;
  const locked = busy || publishing;
  const categoryName = (categoryId: string) =>
    categories.kind === 'ready'
      ? (categories.options.find((option) => option.id === categoryId)?.label ?? null)
      : null;

  /** aria-live 안내 — 같은 문구가 이어져도 다시 읽히게 비운 뒤 넣는다 */
  const announce = (message: string) => {
    setLive('');
    window.setTimeout(() => setLive(message), 50);
  };
  /** 버리기 · 최신 불러오기 뒤 포커스 — 트리 첫 항목, 없으면 화면 제목 */
  const focusFirst = (next: MenuNode[]) => {
    const first = next[0];
    focusTarget.current = {
      target: first ? `[data-select="${first.key}"]` : TITLE,
      fallback: TITLE,
    };
  };

  /** 구조(순서 · 단계 · 개수)가 바뀌면 칸 오류(번호 기준)가 어긋나므로 지운다 */
  const edit = (next: MenuNode[], keepErrors = true) => {
    setTree(next);
    if (!keepErrors) setErrors({});
    setNotice(null);
    setDiscarding(false);
  };

  const moved = (key: string, result: MoveResult, focus: string) => {
    if (!result.ok) {
      announce(MOVE_PROBLEMS[result.problem]);
      return;
    }
    edit(result.tree, false);
    const where = placementText(result.tree, key, (node) => nodeName(node, locale));
    announce(
      result.cleared
        ? `${where} 하위 메뉴가 되어 PC 1차 메뉴 · 모바일 바로가기를 껐습니다.`
        : where,
    );
    focusTarget.current = { target: focus, fallback: `[data-handle="${key}"]` };
  };
  const move = (key: string, direction: MoveDirection, focus: string) =>
    moved(key, moveBy(tree, key, direction), focus);
  const drop = (key: string, target: MoveTarget) =>
    moved(key, relocate(tree, key, target), `[data-handle="${key}"]`);

  const add = (parentKey: string | null) => {
    const result = addNode(tree, parentKey);
    if (!result.ok) {
      announce(MOVE_PROBLEMS[result.problem]);
      return;
    }
    edit(result.tree, false);
    setSelectedKey(result.key);
    announce(parentKey === null ? '새 메뉴를 맨 뒤에 더했습니다.' : '하위 메뉴를 더했습니다.');
    focusTarget.current = { target: `[data-select="${result.key}"]` };
  };

  const begin = (kind: 'save' | 'publish') => {
    if (sending.current) return false;
    sending.current = true;
    setPending(kind);
    return true;
  };
  const release = () => {
    sending.current = false;
    setPending(null);
  };

  const applied = (next: NavDetail, message: string) => {
    setLoad({ kind: 'ready', base: next });
    setTree(formOf(next));
    setErrors({});
    setConflict(false);
    setDiscarding(false);
    setNotice({ tone: 'info', text: message });
  };

  /** 경합 · 결과 모름 — 입력은 두고 기준 상세만 바꾼다(다음 저장은 최신 version 으로) */
  const rebase = (message: string, isConflict: boolean) => {
    setNotice({ tone: 'error', text: message });
    return loadDetail().then(
      (next) => {
        setLoad({ kind: 'ready', base: next });
        setConflict(isConflict);
      },
      (cause: unknown) => {
        const denied = accessDeniedOf(cause);
        if (denied === 'forbidden') setLoad({ kind: 'denied', denied });
        else setNotice({ tone: 'error', text: `${message} ${LOAD_FAILED}` });
      },
    );
  };

  const writeFailed = (sent: readonly NavItemPayload[], isPublish: boolean) => (cause: unknown) => {
    const failure = writeFailure(cause, sent, isPublish);
    if (failure.kind === 'access') {
      release();
      setLoad({ kind: 'denied', denied: failure.denied });
    } else if (failure.kind === 'reload') {
      setPublishing(false);
      void rebase(failure.notice, failure.conflict).finally(release);
    } else {
      release();
      setErrors(failure.errors);
    }
  };

  const save = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const invalid = draftErrors(tree);
    if (Object.keys(invalid).length > 0) {
      setErrors({ ...invalid, form: [CHECK_FIELDS] });
      setNotice(null);
      // 첫 오류 항목을 골라 상세 칸에서 바로 고칠 수 있게 한다
      const first = Object.keys(invalid)
        .map((key) => /^items\.(\d+)\./.exec(key)?.[1])
        .find((value) => value !== undefined);
      const row = first === undefined ? undefined : flatten(tree)[Number(first)];
      if (row) setSelectedKey(row.node.key);
      return;
    }
    if (!begin('save')) return;
    setErrors({});
    setNotice(null);
    const items = contentOf(tree);
    apiFetch<NavDetail>(STORE_NAVIGATION_PATH, {
      method: 'PUT',
      json: { expectedVersion: base?.version ?? null, items } satisfies NavDraftRequest,
    }).then(
      (next) => {
        release();
        applied(
          next,
          `${next.latestRevision}번 초안을 저장했습니다. 게시하기 전까지 고객에게 보이지 않습니다.`,
        );
      },
      writeFailed(items, false),
    );
  };

  const publish = () => {
    if (!base || !begin('publish')) return;
    setErrors({});
    setNotice(null);
    const revisionNumber = base.latestRevision;
    const done = (next: NavDetail) => {
      release();
      setPublishing(false);
      applied(next, `${revisionNumber}번을 게시했습니다. 스토어프론트 헤더에 바로 반영됩니다.`);
    };
    apiFetch<NavDetail>(STORE_NAVIGATION_PUBLISH_PATH, {
      method: 'POST',
      json: { expectedVersion: base.version, revisionNumber } satisfies NavPublishRequest,
    }).then(done, (cause: unknown) => {
      const sent = base.revision.items;
      if (!publishNeedsReload(cause, sent)) {
        writeFailed(sent, true)(cause);
        return;
      }
      // 409 · 결과 모름 — 다시 읽어 그 번호가 공개 중이면 성공(같은 리비전 동시 게시의 한쪽 409, BE 계약),
      // 아니면 화면을 최신 저장본으로 바꾼다(`publishOutcome`). 게시는 저장한 초안만 하므로 지킬 입력이 없다 —
      // 초안 저장(PUT)의 409 는 `writeFailed` 로 입력을 지킨다
      setPublishing(false);
      loadDetail().then(
        (next) => {
          const outcome = publishOutcome(cause, next, revisionNumber, sent);
          if (outcome.kind === 'done') {
            if (next) done(next);
            return;
          }
          release();
          if (outcome.kind === 'reload') {
            const reset = formOf(next);
            setLoad({ kind: 'ready', base: next });
            setTree(reset);
            setErrors({});
            setConflict(false);
            setSelectedKey((key) => (key && findRow(reset, key) ? key : null));
            setNotice({ tone: 'error', text: outcome.notice });
          } else if (outcome.kind === 'access') setLoad({ kind: 'denied', denied: outcome.denied });
          else setErrors(outcome.errors);
        },
        (loadCause: unknown) => {
          release();
          const denied = accessDeniedOf(loadCause);
          if (denied === 'forbidden') setLoad({ kind: 'denied', denied });
          else setNotice({ tone: 'error', text: `${PUBLISH_RELOAD_FAILED} ${LOAD_FAILED}` });
        },
      );
    });
  };

  const rows = flatten(tree);
  const selected = selectedKey ? findRow(tree, selectedKey) : null;
  const selectedIndex = selected ? rows.findIndex((row) => row.node.key === selected.node.key) : -1;
  const state = publishState(tree, base);
  const total = countAll(tree);
  const changes = dirty ? changeCount(tree, base) : 0;
  const movable = (key: string) =>
    Object.fromEntries(
      DIRECTIONS.map((direction) => [direction, !locked && moveBy(tree, key, direction).ok]),
    ) as Record<MoveDirection, boolean>;

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h1 data-nav-title tabIndex={-1} className="text-xl font-semibold outline-none">
          메뉴
        </h1>
        <p className="text-sm text-muted-fg">
          스토어프론트 헤더의 메뉴 이름 · 링크 · 순서 · 노출을 국문 · 영문 함께 편집합니다. 초안
          저장은 고객에게 공개되지 않고 <strong>게시</strong>해야 바뀝니다. 메뉴는 3단계까지, 숨김을
          포함해 {LIMITS.items}개까지 둘 수 있습니다.
        </p>
      </div>

      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <p role="status" className="text-sm">
          {publicStateText(base)}
          {dirty && ` 저장하지 않은 변경 ${changes}건이 있습니다.`}
        </p>
        {dirty && !discarding && (
          <button
            type="button"
            disabled={locked}
            className="text-sm underline underline-offset-4 disabled:opacity-50"
            onClick={() => setDiscarding(true)}
          >
            저장하지 않은 변경 버리기
          </button>
        )}
        {dirty && discarding && (
          <span
            role="alertdialog"
            aria-label="변경 버리기 확인"
            className="flex items-center gap-3 text-sm"
          >
            마지막으로 저장한 {base ? `${base.latestRevision}번 초안` : '상태(빈 메뉴)'}으로
            돌아갈까요?
            <button
              type="button"
              className="font-medium text-danger underline-offset-4 hover:underline"
              onClick={() => {
                const reset = formOf(base);
                setTree(reset);
                setErrors({});
                setDiscarding(false);
                setConflict(false);
                setSelectedKey((key) => (key && findRow(reset, key) ? key : null));
                setNotice({ tone: 'info', text: '저장하지 않은 변경을 버렸습니다.' });
                focusFirst(reset);
              }}
            >
              버리기
            </button>
            <button
              type="button"
              className="underline-offset-4 hover:underline"
              onClick={() => setDiscarding(false)}
            >
              취소
            </button>
          </span>
        )}
      </div>

      {notice && (
        <p
          role={notice.tone === 'error' ? 'alert' : 'status'}
          className={cx('text-sm', notice.tone === 'error' ? 'text-danger' : 'text-fg')}
        >
          {notice.text}
        </p>
      )}

      {conflict && (
        <div className="flex flex-wrap items-center gap-3 rounded-md border border-border px-4 py-3 text-sm">
          <span>
            최신 저장본은 {base ? `${base.latestRevision}번` : '없음'}입니다. 최신 내용을 불러오면
            지금 변경은 사라집니다.
          </span>
          <Button
            variant="secondary"
            onClick={() => {
              const reset = formOf(base);
              setTree(reset);
              setConflict(false);
              setErrors({});
              setSelectedKey(null);
              setNotice({ tone: 'info', text: '최신 내용을 불러왔습니다.' });
              focusFirst(reset);
            }}
          >
            최신 내용 불러오기
          </Button>
        </div>
      )}

      {/* 옮기기 · 더하기 결과 안내(스크린리더) */}
      <p aria-live="polite" className="sr-only">
        {live}
      </p>

      <form noValidate onSubmit={save} className="flex flex-col gap-6">
        <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
          <section
            aria-labelledby={`${id}-tree`}
            data-tour="nav-tree"
            className="flex min-w-0 flex-col gap-3"
          >
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h2 id={`${id}-tree`} className="font-semibold">
                메뉴 구조 ({total}/{LIMITS.items})
              </h2>
              <Button
                variant="secondary"
                data-add-root
                disabled={locked || total >= LIMITS.items}
                onClick={() => add(null)}
              >
                메뉴 추가
              </Button>
            </div>
            <p className="text-xs text-muted-fg">
              손잡이를 끌어 옮기고, 오른쪽으로 끌면 바로 위 항목의 하위 메뉴가 됩니다. 키보드는
              손잡이에서 Alt + 화살표입니다. 항목 이름을 누르면 오른쪽에서 고칩니다.
            </p>
            {total >= LIMITS.items && (
              <p className="text-xs text-muted-fg">{MOVE_PROBLEMS.total}</p>
            )}
            <FieldError messages={errors.items ?? []} />
            <StoreNavigationTree
              tree={tree}
              selectedKey={selected ? selected.node.key : null}
              locale={locale}
              errors={errors}
              locked={locked}
              categoryName={categoryName}
              onSelect={setSelectedKey}
              onToggleVisible={(key, visible) =>
                edit(updateNode(tree, key, (node) => ({ ...node, visible })))
              }
              onMove={(key, direction) => move(key, direction, `[data-handle="${key}"]`)}
              onDrop={drop}
              onAnnounce={announce}
            />
          </section>

          <section
            aria-labelledby={`${id}-detail`}
            data-tour="nav-detail"
            className="flex min-w-0 flex-col gap-3"
          >
            <h2 id={`${id}-detail`} className="font-semibold">
              선택한 항목
            </h2>
            {selected ? (
              <ItemDetail
                key={selected.node.key}
                node={selected.node}
                depth={selected.depth}
                index={selectedIndex}
                errors={errors}
                locked={locked}
                categories={categories}
                movable={movable(selected.node.key)}
                canAddChild={
                  !locked &&
                  selected.depth + 1 < LIMITS.depth &&
                  selected.node.children.length < LIMITS.siblings &&
                  total < LIMITS.items
                }
                onChange={(next) => {
                  edit(updateNode(tree, next.key, () => next));
                  // 고친 칸의 서버 · 저장 전 오류만 지운다(다른 칸 오류는 남긴다)
                  setErrors((current) =>
                    clearedErrors(current, selectedIndex, selected.node, next),
                  );
                }}
                onMove={(direction) =>
                  move(
                    selected.node.key,
                    direction,
                    `[data-position="${direction}:${selected.node.key}"]`,
                  )
                }
                onAddChild={() => add(selected.node.key)}
                onRemove={() => {
                  const name = nodeName(selected.node, locale);
                  // 포커스는 다음 형제 → 이전 형제 → 상위 → "메뉴 추가" 버튼
                  const neighbor = focusAfterRemove(tree, selected.node.key);
                  edit(removeNode(tree, selected.node.key), false);
                  setSelectedKey(null);
                  announce(`${name}을(를) 지웠습니다.`);
                  focusTarget.current = {
                    target: neighbor ? `[data-select="${neighbor}"]` : ADD_ROOT,
                    fallback: TITLE,
                  };
                }}
              />
            ) : (
              <p className="text-sm text-muted-fg">
                왼쪽에서 항목을 고르면 표시명 · 링크 · 노출을 여기서 고칩니다.
              </p>
            )}
          </section>
        </div>

        {!publishing && <FieldError messages={errors.form ?? []} />}
        <div className="flex flex-col gap-1" data-tour="nav-save">
          <div>
            <Button type="submit" disabled={locked || (!dirty && base !== null)}>
              {pending === 'save' ? '저장 중…' : '초안 저장 (공개 안 됨)'}
            </Button>
          </div>
          <p className="text-xs text-muted-fg">
            저장할 때마다 새 리비전이 쌓입니다. 스토어프론트는 게시할 때만 바뀝니다.
          </p>
        </div>
      </form>

      <section
        aria-labelledby={`${id}-preview`}
        data-tour="nav-preview"
        className="flex flex-col gap-3 border-t border-border pt-6"
      >
        <h2 id={`${id}-preview`} className="font-semibold">
          미리보기 (저장 전 입력)
        </h2>
        <div className="flex flex-wrap gap-4">
          <div role="group" aria-label="미리보기 화면 크기" className="flex gap-1">
            {(['pc', 'mobile'] as const).map((value) => (
              <button
                key={value}
                type="button"
                aria-pressed={device === value}
                className={toggleButton(device === value)}
                onClick={() => setDevice(value)}
              >
                {value === 'pc' ? 'PC' : '모바일'}
              </button>
            ))}
          </div>
          <div role="group" aria-label="미리보기 언어" className="flex gap-1">
            {(['ko', 'en'] as const).map((value) => (
              <button
                key={value}
                type="button"
                aria-pressed={locale === value}
                className={toggleButton(locale === value)}
                onClick={() => setLocale(value)}
              >
                {value === 'ko' ? '국문' : '영문'}
              </button>
            ))}
          </div>
        </div>
        <MenuPreview tree={tree} device={device} locale={locale} />
      </section>

      {base && (
        <section
          aria-label="게시"
          data-tour="nav-publish"
          className="flex flex-col gap-2 border-t border-border pt-6"
        >
          {state === 'published' && (
            <p className="text-sm">최신 {base.latestRevision}번이 이미 공개 중입니다.</p>
          )}
          {state === 'dirty' && (
            <p className="text-sm text-muted-fg">
              저장하지 않은 입력이 있습니다. 게시는 저장한 최신 초안만 할 수 있으니 먼저 초안을
              저장해 주세요.
            </p>
          )}
          {state === 'ready' && !publishing && (
            <div className="flex flex-wrap items-center gap-3">
              <Button
                onClick={() => {
                  setPublishing(true);
                  setErrors({});
                }}
              >
                {base.latestRevision}번 게시하기…
              </Button>
              <span className="text-xs text-muted-fg">게시 전에는 고객에게 보이지 않습니다.</span>
            </div>
          )}
          {state === 'ready' && publishing && (
            <>
              <PublishConfirm
                revisionNumber={base.latestRevision}
                pending={pending === 'publish'}
                onCancel={() => setPublishing(false)}
                onPublish={publish}
              />
              <FieldError messages={errors.form ?? []} />
            </>
          )}
        </section>
      )}
    </div>
  );
}
