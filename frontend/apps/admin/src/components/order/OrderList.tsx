'use client';

import { apiFetch, type PageResponse } from '@cf/api-client';
import { Button } from '@cf/ui';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import { hasFilter, ordersApiPath, ordersHref, type OrderQuery } from '@/lib/order/query';
import type { OrderListItem } from '@/lib/order/types';
import { BulkStatusPanel } from './BulkStatusPanel';
import { OrderFilters } from './OrderFilters';
import { OrderTable } from './OrderTable';

/** `reload` — 어느 다시 읽기의 응답인지. 지금 값과 다르면 일괄 변경 뒤 새 목록을 기다리는 중이다 */
type Loaded =
  | { path: string; reload: number; kind: 'ok'; data: PageResponse<OrderListItem> }
  | { path: string; reload: number; kind: 'failed' }
  | { path: string; reload: number; kind: 'denied'; denied: AccessDenied };

/**
 * 어드민 주문 목록(#30) — `GET /api/v1/admin/orders`. 조건은 주소(`query`)가 정본이다. 최신순(주문 시각 · 아이디 내림차순)이다.
 * 표에서 고른 주문은 아래 일괄 변경으로 보낸다. 고른 것은 지금 페이지(주소)에만 걸린다 — 페이지 · 조건이 바뀌면 비운다.
 * 받는 분은 서버가 가린 값이다. 결제수단 조건 · 결제 · 송장 · 클레임은 BE 가 열리면(#14 · #18 · #15) 붙인다.
 */
export function OrderList({ query }: { query: OrderQuery }) {
  const path = ordersApiPath(query);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [reload, setReload] = useState(0);
  const [selection, setSelection] = useState<{ path: string; ids: ReadonlySet<string> }>({
    path,
    ids: new Set(),
  });
  const [denied, setDenied] = useState<AccessDenied | null>(null);

  // 상태는 응답 콜백에서만 바꾼다. 처음 읽는 중은 `loaded.path !== path` 로 그린다(늦게 온 이전 응답은 버린다)
  useEffect(() => {
    let alive = true;
    apiFetch<PageResponse<OrderListItem>>(path).then(
      (data) => alive && setLoaded({ path, reload, kind: 'ok', data }),
      (cause: unknown) => {
        if (!alive) return;
        const kind = accessDeniedOf(cause);
        setLoaded(
          kind ? { path, reload, kind: 'denied', denied: kind } : { path, reload, kind: 'failed' },
        );
      },
    );
    return () => {
      alive = false;
    };
  }, [path, reload]);

  const current = loaded?.path === path ? loaded : null;
  // 바뀌기 전 목록을 보고 다시 고르지 않게, 새 목록이 올 때까지 고르기를 막고 표시한다
  const refreshing = current !== null && current.reload !== reload;
  const selected = selection.path === path ? selection.ids : new Set<string>();
  const orders = current?.kind === 'ok' ? current.data.contents : [];
  const selectedOrders = orders.filter((order) => selected.has(order.orderId));

  const toggle = (orderId: string) => {
    const ids = new Set(selected);
    if (!ids.delete(orderId)) ids.add(orderId);
    setSelection({ path, ids });
  };
  const toggleAll = (checked: boolean) =>
    setSelection({
      path,
      ids: checked ? new Set(orders.map((order) => order.orderId)) : new Set(),
    });

  if (denied) return <AccessNotice denied={denied} />;

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold">주문</h1>
        <p className="mt-1 text-sm text-muted-fg">
          주문을 찾고 상태를 바꿉니다. 주문 이름을 누르면 상세 · 이력을 봅니다. 받는 분은 가려서
          보입니다.
        </p>
      </div>

      <OrderFilters key={ordersHref(query)} query={query} />

      {!current && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {current?.kind === 'denied' && <AccessNotice denied={current.denied} />}
      {current?.kind === 'failed' && (
        <div role="alert" className="flex items-center gap-4">
          <p className="text-sm text-danger">{LOAD_FAILED}</p>
          <Button variant="secondary" onClick={() => setReload((n) => n + 1)}>
            다시 불러오기
          </Button>
        </div>
      )}
      {current?.kind === 'ok' &&
        current.data.contents.length === 0 &&
        (current.data.totalElements > 0 ? (
          // 손으로 고친 주소 · 그사이 줄어든 목록 — 주문은 있으니 "없다" 고 하지 않고 돌아갈 길을 준다
          <p className="text-sm text-muted-fg">
            이 페이지에는 주문이 없습니다.{' '}
            <Link
              href={ordersHref({ ...query, page: 0 })}
              className="text-fg underline underline-offset-4"
            >
              첫 페이지로
            </Link>
          </p>
        ) : (
          <p className="text-sm text-muted-fg">
            {hasFilter(query) ? '조건에 맞는 주문이 없습니다.' : '아직 주문이 없습니다.'}
          </p>
        ))}
      {current?.kind === 'ok' && current.data.contents.length > 0 && (
        <>
          <p className="text-sm text-muted-fg" aria-live="polite">
            {current.data.totalElements}건{refreshing && ' · 갱신 중…'}
          </p>
          <OrderTable
            orders={current.data.contents}
            disabled={refreshing}
            selected={selected}
            onToggle={toggle}
            onToggleAll={toggleAll}
          />
          {current.data.totalPages > 1 && (
            <nav aria-label="페이지" className="flex items-center gap-3 text-sm">
              <PageLink query={query} page={query.page - 1} disabled={query.page === 0}>
                이전
              </PageLink>
              <span>
                {query.page + 1} / {current.data.totalPages}
              </span>
              <PageLink
                query={query}
                page={query.page + 1}
                disabled={query.page + 1 >= current.data.totalPages}
              >
                다음
              </PageLink>
            </nav>
          )}
        </>
      )}

      {/* 결과가 다시 읽은 목록 · 다른 페이지에서도 남도록 목록 상태와 따로 둔다 */}
      {current?.kind !== 'denied' && (
        <BulkStatusPanel
          selected={selectedOrders}
          onSent={() => {
            setSelection({ path, ids: new Set() });
            setReload((n) => n + 1);
          }}
          onDenied={setDenied}
        />
      )}
    </div>
  );
}

function PageLink({
  query,
  page,
  disabled,
  children,
}: {
  query: OrderQuery;
  page: number;
  disabled: boolean;
  children: string;
}) {
  if (disabled) return <span className="text-muted-fg">{children}</span>;
  return (
    <Link
      href={ordersHref({ ...query, page })}
      className="rounded-md bg-accent px-3 py-1.5 text-accent-fg hover:opacity-90"
    >
      {children}
    </Link>
  );
}
