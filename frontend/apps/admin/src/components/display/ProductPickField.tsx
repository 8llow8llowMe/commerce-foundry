'use client';

import { apiFetch, ApiError, type PageResponse } from '@cf/api-client';
import { Button, FieldError } from '@cf/ui';
import { useEffect, useId, useRef, useState } from 'react';
import { LOAD_FAILED } from '@/lib/api-errors';
import type { ProductFormItem } from '@/lib/display/form';
import { moveItem } from '@/lib/display/order';
import { addProduct, productNotice } from '@/lib/display/products';
import { ITEM_RANGES, type SectionTypeCode } from '@/lib/display/types';
import { ADMIN_PRODUCTS_PATH, KEYWORD_MAX, type ProductListItem } from '@/lib/product/query';

const SEARCH_SIZE = 10;

type Search = { keyword: string; page: number };
type Result =
  | { search: Search; kind: 'ok'; data: PageResponse<ProductListItem> }
  | { search: Search; kind: 'failed'; message: string };

const smallButton =
  'flex h-8 items-center justify-center rounded px-2 text-sm hover:bg-muted disabled:cursor-not-allowed disabled:opacity-30';

/** 검색 실패 문구 — 폼 안이라 401 이어도 로그인 화면으로 보내지 않는다(입력을 지킨다) */
function searchFailure(cause: unknown): string {
  if (cause instanceof ApiError && cause.kind === 'unauthorized') {
    return '로그인이 만료되었습니다. 새 탭에서 어드민에 다시 로그인한 뒤 검색해 주세요.';
  }
  if (cause instanceof ApiError && cause.kind === 'forbidden') return '관리자 권한이 없습니다.';
  return LOAD_FAILED;
}

/**
 * 상품 픽의 상품 — 어드민 상품 목록(#9, 숨김 포함)에서 찾아 더하고, 빼고, 순서를 바꾼다. 상한은 BE 유형 규칙(상품 픽 1~20개)이다.
 * 숨긴 상품은 등록은 되지만 메인에서 빠지고, 판매중이 아닌 상품은 메인에 보이지만 살 수 없다 — 두 경우 모두 그 줄에 알린다.
 */
export function ProductPickField({
  type,
  products,
  errors,
  onChange,
}: {
  type: SectionTypeCode;
  products: ProductFormItem[];
  errors: string[];
  onChange: (products: ProductFormItem[]) => void;
}) {
  const id = useId();
  const max = ITEM_RANGES[type].product[1];
  const [draft, setDraft] = useState('');
  const [search, setSearch] = useState<Search | null>(null);
  const [result, setResult] = useState<Result | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const listRef = useRef<HTMLUListElement>(null);

  // 상태는 응답 콜백에서만 바꾼다. 늦게 온 이전 검색 결과는 버린다
  useEffect(() => {
    if (!search) return;
    let alive = true;
    const params = new URLSearchParams({
      sortType: 'NEWEST',
      page: String(search.page),
      size: String(SEARCH_SIZE),
    });
    if (search.keyword) params.set('keyword', search.keyword);
    apiFetch<PageResponse<ProductListItem>>(`${ADMIN_PRODUCTS_PATH}?${params.toString()}`).then(
      (data) => alive && setResult({ search, kind: 'ok', data }),
      (cause: unknown) =>
        alive && setResult({ search, kind: 'failed', message: searchFailure(cause) }),
    );
    return () => {
      alive = false;
    };
  }, [search]);

  const current = result && result.search === search ? result : null;
  const runSearch = () => {
    setSearch({ keyword: draft.trim().slice(0, KEYWORD_MAX), page: 0 });
    setMessage(null);
  };

  const add = (product: ProductListItem) => {
    const added = addProduct(products, product, type);
    if (!added.ok) {
      setMessage(
        added.reason === 'duplicate'
          ? `‘${product.name}’ 은(는) 이미 넣은 상품입니다.`
          : `상품은 ${max}개까지 넣을 수 있습니다.`,
      );
      return;
    }
    onChange(added.products);
    setMessage(`‘${product.name}’ 을(를) 맨 뒤에 넣었습니다.`);
  };

  const move = (from: number, to: number) => {
    onChange(moveItem(products, from, to));
    const name = products[from]?.name ?? '상품';
    setMessage(`‘${name}’ 을(를) ${to + 1}번째로 옮겼습니다.`);
    // 옮긴 줄의 같은 버튼으로 포커스 — 끝에 닿아 막혔으면 반대 버튼으로
    const productId = products[from]?.productId;
    requestAnimationFrame(() => {
      const row = listRef.current?.querySelector<HTMLElement>(
        `[data-product="${CSS.escape(productId ?? '')}"]`,
      );
      const buttons = [...(row?.querySelectorAll<HTMLButtonElement>('button[data-move]') ?? [])];
      const preferred = buttons.find(
        (button) => button.dataset.move === (to < from ? 'up' : 'down') && !button.disabled,
      );
      (preferred ?? buttons.find((button) => !button.disabled))?.focus();
    });
  };

  const remove = (index: number) => {
    const name = products[index]?.name ?? '상품';
    onChange(products.filter((_, at) => at !== index));
    setMessage(`‘${name}’ 을(를) 뺐습니다.`);
  };

  const added = new Set(products.map((item) => item.productId));

  return (
    <fieldset className="flex min-w-0 flex-col gap-3" aria-describedby={`${id}-hint`}>
      <legend className="text-sm font-medium">
        상품 ({products.length} / {max}개)
      </legend>
      <p id={`${id}-hint`} className="text-xs text-muted-fg">
        메인에 이 순서대로 보입니다. 1개 이상 {max}개까지 넣을 수 있습니다.
      </p>

      {products.length === 0 ? (
        <p className="text-sm text-muted-fg">아직 넣은 상품이 없습니다. 아래에서 찾아 넣으세요.</p>
      ) : (
        <ul ref={listRef} className="flex flex-col gap-1" aria-label="넣은 상품">
          {products.map((item, index) => {
            const name = item.name ?? '찾을 수 없는 상품';
            const notice = productNotice(item);
            return (
              <li
                key={item.productId}
                data-product={item.productId}
                className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-md bg-bg px-3 py-2"
              >
                <span className="w-6 text-xs text-muted-fg">{index + 1}</span>
                <div className="flex min-w-0 flex-1 flex-col">
                  <span className="truncate text-sm font-medium">{name}</span>
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
                    disabled={index === products.length - 1}
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
        {/* 폼 안에 폼을 둘 수 없어 검색은 버튼 · 엔터 키로 직접 부른다(바깥 폼을 제출하지 않는다) */}
        <div className="flex items-end gap-2">
          <div className="flex flex-1 flex-col gap-1">
            <label htmlFor={`${id}-keyword`} className="text-sm font-medium">
              상품 찾기
            </label>
            <input
              id={`${id}-keyword`}
              type="search"
              maxLength={KEYWORD_MAX}
              value={draft}
              placeholder="상품명"
              onChange={(event) => setDraft(event.currentTarget.value)}
              onKeyDown={(event) => {
                if (event.key !== 'Enter') return;
                event.preventDefault();
                runSearch();
              }}
              className="h-10 rounded-md border border-border bg-bg px-3 text-fg outline-none focus:ring-2 focus:ring-primary"
            />
          </div>
          <Button variant="secondary" onClick={runSearch}>
            검색
          </Button>
        </div>

        {search && !current && <p className="text-sm text-muted-fg">찾는 중…</p>}
        {current?.kind === 'failed' && (
          <p role="alert" className="text-sm text-danger">
            {current.message}
          </p>
        )}
        {current?.kind === 'ok' && current.data.contents.length === 0 && (
          <p className="text-sm text-muted-fg">
            {current.search.keyword
              ? `‘${current.search.keyword}’ 에 맞는 상품이 없습니다.`
              : '상품이 없습니다.'}
          </p>
        )}
        {current?.kind === 'ok' && current.data.contents.length > 0 && (
          <>
            <ul className="flex flex-col gap-1" aria-label="찾은 상품">
              {current.data.contents.map((product) => {
                const isAdded = added.has(product.productId);
                const state = !product.visible
                  ? '숨김 — 넣어도 메인에 보이지 않습니다'
                  : product.availability.code !== 'ON_SALE'
                    ? `${product.availability.name} — 메인에 보이지만 살 수 없습니다`
                    : product.availability.name;
                return (
                  <li
                    key={product.productId}
                    className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 px-1 py-1 text-sm"
                  >
                    <div className="flex min-w-0 flex-1 flex-col">
                      <span className="truncate">{product.name}</span>
                      <span className="text-xs text-muted-fg">{state}</span>
                    </div>
                    <button
                      type="button"
                      className={smallButton}
                      aria-label={`${product.name} 넣기`}
                      disabled={isAdded || products.length >= max}
                      onClick={() => add(product)}
                    >
                      {isAdded ? '넣음' : '넣기'}
                    </button>
                  </li>
                );
              })}
            </ul>
            {current.data.totalPages > 1 && (
              <div className="flex items-center gap-3 text-sm">
                <Button
                  variant="secondary"
                  disabled={current.search.page === 0}
                  onClick={() => setSearch({ ...current.search, page: current.search.page - 1 })}
                >
                  이전
                </Button>
                <span>
                  {current.search.page + 1} / {current.data.totalPages}
                </span>
                <Button
                  variant="secondary"
                  disabled={current.search.page + 1 >= current.data.totalPages}
                  onClick={() => setSearch({ ...current.search, page: current.search.page + 1 })}
                >
                  다음
                </Button>
              </div>
            )}
          </>
        )}
      </div>
      {/* 넣기 · 빼기 · 옮기기 결과(늘 있는 live region) */}
      <p role="status" className="min-h-4 text-xs text-muted-fg">
        {message}
      </p>
    </fieldset>
  );
}
