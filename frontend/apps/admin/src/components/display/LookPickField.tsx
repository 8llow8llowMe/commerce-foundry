'use client';

import { apiFetch, ApiError } from '@cf/api-client';
import { Button, FieldError } from '@cf/ui';
import { useEffect, useId, useRef, useState } from 'react';
import { LOAD_FAILED } from '@/lib/api-errors';
import { moveItem } from '@/lib/display/order';
import { LOOKS_PATH, type LookAdmin, type LookList } from '@/lib/look/types';

const smallButton =
  'flex h-8 items-center justify-center rounded px-2 text-sm hover:bg-muted disabled:cursor-not-allowed disabled:opacity-30';

type Loaded = { kind: 'ok'; looks: LookAdmin[] } | { kind: 'failed'; message: string };

/** 룩 목록 실패 문구 — 폼 안이라 401 이어도 로그인 화면으로 보내지 않는다(입력을 지킨다) */
function loadFailure(cause: unknown): string {
  if (cause instanceof ApiError && cause.kind === 'unauthorized') {
    return '로그인이 만료되었습니다. 새 탭에서 어드민에 다시 로그인한 뒤 다시 불러와 주세요.';
  }
  if (cause instanceof ApiError && cause.kind === 'forbidden') return '관리자 권한이 없습니다.';
  return LOAD_FAILED;
}

/**
 * 고른 룩의 줄 안내 — 지워진 룩은 저장이 막히고(`DISPLAY_001`), 숨긴 룩 · 상품이 모두 빠진 룩은 저장되지만 메인에 그만큼 덜 보인다.
 * 목록을 아직 못 읽었으면(undefined) 안내하지 않는다.
 */
export function lookNotice(look: LookAdmin | null | undefined): string | null {
  if (look === undefined) return null;
  if (look === null) return '찾을 수 없는 룩입니다(지워졌을 수 있습니다). 빼고 저장해 주세요.';
  if (!look.visible) return '숨긴 룩이라 메인에 보이지 않습니다. 룩 관리에서 노출을 켜 주세요.';
  if (look.products.length > 0 && look.products.every((product) => !product.visible)) {
    return '구성 상품이 모두 숨김이라 메인에는 화보만 보입니다.';
  }
  return null;
}

/**
 * 메인 전시 룩 섹션의 룩 고르기(BE #372) — 룩 관리(#406)의 룩을 골라 넣고, 빼고, 순서를 바꾼다. 화보 · 구성 상품은 룩 관리에서 고친다.
 * 섹션 응답에는 룩 아이디만 있어 이름 · 노출 여부는 어드민 룩 목록(`GET /api/v1/admin/looks`, 숨김 포함 전부)으로 보인다.
 * 하한 · 상한은 BE 규칙이다(`ITEM_RANGES.LOOK.look`).
 */
export function LookPickField({
  min,
  max,
  lookIds,
  errors,
  onChange,
}: {
  min: number;
  max: number;
  lookIds: string[];
  errors: string[];
  onChange: (lookIds: string[]) => void;
}) {
  const id = useId();
  const [attempt, setAttempt] = useState(0);
  const [loaded, setLoaded] = useState<{ attempt: number; result: Loaded } | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const listRef = useRef<HTMLUListElement>(null);

  // 상태는 응답 콜백에서만 바꾼다. 다시 불러오기 전의 늦은 응답은 버린다
  useEffect(() => {
    let alive = true;
    apiFetch<LookList>(LOOKS_PATH).then(
      (data) => alive && setLoaded({ attempt, result: { kind: 'ok', looks: data.looks } }),
      (cause: unknown) =>
        alive && setLoaded({ attempt, result: { kind: 'failed', message: loadFailure(cause) } }),
    );
    return () => {
      alive = false;
    };
  }, [attempt]);

  const current = loaded && loaded.attempt === attempt ? loaded.result : null;
  const looks = current?.kind === 'ok' ? current.looks : null;
  const byId = new Map(looks?.map((look) => [look.lookId, look]));
  /** 목록을 읽었으면 그 룩(없으면 null), 못 읽었으면 undefined */
  const lookOf = (lookId: string) => (looks ? (byId.get(lookId) ?? null) : undefined);
  const nameOf = (lookId: string) => lookOf(lookId)?.title ?? '이름을 불러오지 못한 룩';

  const add = (look: LookAdmin) => {
    if (lookIds.includes(look.lookId)) {
      setMessage(`‘${look.title}’ 은(는) 이미 넣은 룩입니다.`);
      return;
    }
    if (lookIds.length >= max) {
      setMessage(`룩은 ${max}개까지 넣을 수 있습니다.`);
      return;
    }
    onChange([...lookIds, look.lookId]);
    setMessage(`‘${look.title}’ 을(를) 맨 뒤에 넣었습니다.`);
  };

  const move = (from: number, to: number) => {
    const lookId = lookIds[from] ?? '';
    onChange(moveItem(lookIds, from, to));
    setMessage(`‘${nameOf(lookId)}’ 을(를) ${to + 1}번째로 옮겼습니다.`);
    // 옮긴 줄의 같은 버튼으로 포커스 — 끝에 닿아 막혔으면 반대 버튼으로(`ProductPickField` 와 같다)
    requestAnimationFrame(() => {
      const row = listRef.current?.querySelector<HTMLElement>(
        `[data-look="${CSS.escape(lookId)}"]`,
      );
      const buttons = [...(row?.querySelectorAll<HTMLButtonElement>('button[data-move]') ?? [])];
      const preferred = buttons.find(
        (button) => button.dataset.move === (to < from ? 'up' : 'down') && !button.disabled,
      );
      (preferred ?? buttons.find((button) => !button.disabled))?.focus();
    });
  };

  const remove = (index: number) => {
    const lookId = lookIds[index] ?? '';
    onChange(lookIds.filter((_, at) => at !== index));
    setMessage(`‘${nameOf(lookId)}’ 을(를) 뺐습니다.`);
  };

  return (
    <fieldset className="flex min-w-0 flex-col gap-3" aria-describedby={`${id}-hint`}>
      <legend className="text-sm font-medium">
        룩 ({lookIds.length} / {max}개)
      </legend>
      <p id={`${id}-hint`} className="text-xs text-muted-fg">
        메인에 이 순서대로 보입니다. {min}개 이상 {max}개까지 넣을 수 있습니다. 화보 · 구성 상품은
        룩 관리 화면에서 고칩니다.
      </p>

      {lookIds.length === 0 ? (
        <p className="text-sm text-muted-fg">아직 넣은 룩이 없습니다. 아래에서 골라 넣으세요.</p>
      ) : (
        <ul ref={listRef} className="flex flex-col gap-1" aria-label="넣은 룩">
          {lookIds.map((lookId, index) => {
            const look = lookOf(lookId);
            const name = look === null ? '찾을 수 없는 룩' : nameOf(lookId);
            const notice = lookNotice(look);
            return (
              <li
                key={lookId}
                data-look={lookId}
                className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-md bg-bg px-3 py-2"
              >
                <span className="w-6 text-xs text-muted-fg">{index + 1}</span>
                <div className="flex min-w-0 flex-1 flex-col">
                  <span className="truncate text-sm font-medium">{name}</span>
                  {look && (
                    <span className="text-xs text-muted-fg">상품 {look.products.length}개</span>
                  )}
                  {notice && <span className="text-xs text-danger">{notice}</span>}
                </div>
                <div className="flex items-center gap-1">
                  <button
                    type="button"
                    data-move="up"
                    className={smallButton}
                    aria-label={`${name} 위로`}
                    disabled={index === 0}
                    onClick={() => move(index, index - 1)}
                  >
                    ↑
                  </button>
                  <button
                    type="button"
                    data-move="down"
                    className={smallButton}
                    aria-label={`${name} 아래로`}
                    disabled={index === lookIds.length - 1}
                    onClick={() => move(index, index + 1)}
                  >
                    ↓
                  </button>
                  <button
                    type="button"
                    className={`${smallButton} text-danger`}
                    aria-label={`${name} 빼기`}
                    onClick={() => remove(index)}
                  >
                    빼기
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      )}
      <FieldError messages={errors} />

      <div className="flex flex-col gap-2 rounded-md bg-bg p-3">
        <p className="text-sm font-medium">룩 고르기</p>
        {!current && <p className="text-sm text-muted-fg">불러오는 중…</p>}
        {current?.kind === 'failed' && (
          <div role="alert" className="flex flex-wrap items-center gap-3">
            <p className="text-sm text-danger">{current.message}</p>
            <Button variant="secondary" onClick={() => setAttempt((value) => value + 1)}>
              다시 불러오기
            </Button>
          </div>
        )}
        {looks?.length === 0 && (
          <p className="text-sm text-muted-fg">
            등록된 룩이 없습니다. 룩 관리 화면에서 먼저 룩을 만들어 주세요.
          </p>
        )}
        {looks && looks.length > 0 && (
          <ul className="flex flex-col gap-1" aria-label="등록된 룩">
            {looks.map((look) => {
              const isAdded = lookIds.includes(look.lookId);
              const state = look.visible
                ? `노출 중 · 상품 ${look.products.length}개`
                : `숨김 — 넣어도 메인에 보이지 않습니다 · 상품 ${look.products.length}개`;
              return (
                <li
                  key={look.lookId}
                  className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 px-1 py-1 text-sm"
                >
                  <div className="flex min-w-0 flex-1 flex-col">
                    <span className="truncate">{look.title}</span>
                    <span className="text-xs text-muted-fg">{state}</span>
                  </div>
                  <button
                    type="button"
                    className={smallButton}
                    aria-label={`${look.title} 넣기`}
                    disabled={isAdded || lookIds.length >= max}
                    onClick={() => add(look)}
                  >
                    {isAdded ? '넣음' : '넣기'}
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </div>
      {/* 넣기 · 빼기 · 옮기기 결과(늘 있는 live region) */}
      <p role="status" className="min-h-4 text-xs text-muted-fg">
        {message}
      </p>
    </fieldset>
  );
}
