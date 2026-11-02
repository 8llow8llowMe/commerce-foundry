'use client';

import { apiFetch, type PageResponse } from '@cf/api-client';
import { Button } from '@cf/ui';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import { hasFilter, paymentsApiPath, paymentsHref, type PaymentQuery } from '@/lib/payment/query';
import type { PaymentListItem } from '@/lib/payment/types';
import { PaymentFilters } from './PaymentFilters';
import { PaymentTable } from './PaymentTable';

/** `reload` — 어느 다시 읽기의 응답인지. "다시 불러오기" 뒤 새 응답을 기다리는 중을 가른다 */
type Loaded =
  | { path: string; reload: number; kind: 'ok'; data: PageResponse<PaymentListItem> }
  | { path: string; reload: number; kind: 'failed' }
  | { path: string; reload: number; kind: 'denied'; denied: AccessDenied };

/**
 * 어드민 결제 확인 목록(#30 · BE #484) — `GET /api/v1/admin/payments`. 조건은 주소(`query`)가 정본이고 결제 아이디 내림차순이다.
 * 읽기 전용이다 — 이 화면은 PG 재조회 · 재승인 · 취소 · 환불을 하지 않는다(BE 가 열리면 #15 에서 붙인다).
 */
export function PaymentList({ query }: { query: PaymentQuery }) {
  const path = paymentsApiPath(query);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [reload, setReload] = useState(0);

  // 상태는 응답 콜백에서만 바꾼다. 처음 읽는 중은 `loaded.path !== path` 로 그린다(늦게 온 이전 응답은 버린다)
  useEffect(() => {
    let alive = true;
    apiFetch<PageResponse<PaymentListItem>>(path).then(
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
  const refreshing = current !== null && current.reload !== reload;

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold">결제 확인</h1>
        <p className="mt-1 text-sm text-muted-fg">
          저장된 결제 시도와 주문 회신 상태를 봅니다. 결제사에 다시 조회하거나 승인 · 취소하지
          않습니다. 금액은 결제 시도 원금이며 환불 뒤 잔액이 아닙니다. 주문 아이디를 누르면 주문
          상세로 갑니다.
        </p>
      </div>

      <PaymentFilters key={paymentsHref(query)} query={query} />

      {!current && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {current?.kind === 'denied' && <AccessNotice denied={current.denied} />}
      {current?.kind === 'failed' && (
        <div role="alert" className="flex items-center gap-4">
          <p className="text-sm text-danger">{LOAD_FAILED}</p>
          <Button variant="secondary" disabled={refreshing} onClick={() => setReload((n) => n + 1)}>
            다시 불러오기
          </Button>
        </div>
      )}
      {current?.kind === 'ok' &&
        current.data.contents.length === 0 &&
        (current.data.totalElements > 0 ? (
          // 손으로 고친 주소 · 그사이 줄어든 목록 — 결제는 있으니 "없다" 고 하지 않고 돌아갈 길을 준다
          <p className="text-sm text-muted-fg">
            이 페이지에는 결제가 없습니다.{' '}
            <Link
              href={paymentsHref({ ...query, page: 0 })}
              className="text-fg underline underline-offset-4"
            >
              첫 페이지로
            </Link>
          </p>
        ) : (
          <p className="text-sm text-muted-fg">
            {hasFilter(query) ? '조건에 맞는 결제가 없습니다.' : '아직 결제가 없습니다.'}
          </p>
        ))}
      {current?.kind === 'ok' && current.data.contents.length > 0 && (
        <>
          <p className="text-sm text-muted-fg" aria-live="polite">
            {current.data.totalElements}건
          </p>
          <PaymentTable payments={current.data.contents} />
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
    </div>
  );
}

function PageLink({
  query,
  page,
  disabled,
  children,
}: {
  query: PaymentQuery;
  page: number;
  disabled: boolean;
  children: string;
}) {
  if (disabled) return <span className="text-muted-fg">{children}</span>;
  return (
    <Link
      href={paymentsHref({ ...query, page })}
      className="rounded-md bg-accent px-3 py-1.5 text-accent-fg hover:opacity-90"
    >
      {children}
    </Link>
  );
}
