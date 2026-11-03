'use client';

import { apiFetch, type PageResponse } from '@cf/api-client';
import { Button } from '@cf/ui';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import { claimsApiPath, claimsHref, hasFilter, type ClaimQuery } from '@/lib/claim/query';
import type { ClaimListItem } from '@/lib/claim/types';
import { ClaimFilters } from './ClaimFilters';
import { ClaimTable } from './ClaimTable';

/** `reload` — 어느 다시 읽기의 응답인지. "다시 불러오기" 뒤 새 응답을 기다리는 중을 가른다 */
type Loaded =
  | { path: string; reload: number; kind: 'ok'; data: PageResponse<ClaimListItem> }
  | { path: string; reload: number; kind: 'failed' }
  | { path: string; reload: number; kind: 'denied'; denied: AccessDenied };

/**
 * 어드민 클레임 목록(#30 · BE #574) — `GET /api/v1/admin/claims`. 조건은 주소(`query`)가 정본이고 클레임 아이디 내림차순이다.
 * 읽기 전용이다 — 승인 · 거절 · 환불 실행은 BE #17 잔여라 자리를 두지 않는다.
 */
export function ClaimList({ query }: { query: ClaimQuery }) {
  const path = claimsApiPath(query);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [reload, setReload] = useState(0);

  // 상태는 응답 콜백에서만 바꾼다. 처음 읽는 중은 `loaded.path !== path` 로 그린다(늦게 온 이전 응답은 버린다)
  useEffect(() => {
    let alive = true;
    apiFetch<PageResponse<ClaimListItem>>(path).then(
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
        <h1 className="text-xl font-semibold">클레임</h1>
        <p className="mt-1 text-sm text-muted-fg">
          고객이 접수한 반품을 봅니다. 승인 · 거절 · 환불은 아직 이 화면에서 하지 않습니다. 예상
          환불액이 미산정이면 아직 금액이 정해지지 않은 것입니다(0원이 아닙니다).
        </p>
      </div>

      <ClaimFilters key={claimsHref(query)} query={query} />

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
          <p className="text-sm text-muted-fg">
            이 페이지에는 클레임이 없습니다.{' '}
            <Link
              href={claimsHref({ ...query, page: 0 })}
              className="text-fg underline underline-offset-4"
            >
              첫 페이지로
            </Link>
          </p>
        ) : (
          <p className="text-sm text-muted-fg">
            {hasFilter(query) ? '조건에 맞는 클레임이 없습니다.' : '아직 클레임이 없습니다.'}
          </p>
        ))}
      {current?.kind === 'ok' && current.data.contents.length > 0 && (
        <>
          <p className="text-sm text-muted-fg" aria-live="polite">
            {current.data.totalElements}건
          </p>
          <ClaimTable claims={current.data.contents} />
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
  query: ClaimQuery;
  page: number;
  disabled: boolean;
  children: string;
}) {
  if (disabled) return <span className="text-muted-fg">{children}</span>;
  return (
    <Link
      href={claimsHref({ ...query, page })}
      className="rounded-md bg-accent px-3 py-1.5 text-accent-fg hover:opacity-90"
    >
      {children}
    </Link>
  );
}
