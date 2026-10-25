'use client';

import { apiFetch, type PageResponse } from '@cf/api-client';
import { Button } from '@cf/ui';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import { hasFilter, jobsApiPath, jobsHref, type JobQuery } from '@/lib/job/query';
import type { JobItem } from '@/lib/job/types';
import { JobFilters } from './JobFilters';
import { JobTable } from './JobTable';

type Loaded =
  | { path: string; tick: number; kind: 'ok'; data: PageResponse<JobItem> }
  | { path: string; tick: number; kind: 'failed' }
  | { path: string; tick: number; kind: 'denied'; denied: AccessDenied };

/**
 * 어드민 백그라운드 작업 목록(#272) — 등록 최신순, 조건(상태 · 종류 · 페이지)은 주소(`query`)가 정본이다.
 * 작업은 서버 이름(`typeName`), 상태는 서버 `status.name` 과 서버 안내 문구(`notice`)를 그대로 보인다(`JobTable`). 진행 상황은 상세에서 주기적으로 갱신한다 —
 * 목록은 "새로 고침" 으로만 다시 읽는다.
 */
export function JobList({ query }: { query: JobQuery }) {
  const path = jobsApiPath(query);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [tick, setTick] = useState(0);

  // 상태는 응답 콜백에서만 바꾼다. 읽는 중은 path · tick 이 다른 것으로 그린다(늦게 온 이전 응답은 버린다)
  useEffect(() => {
    let alive = true;
    apiFetch<PageResponse<JobItem>>(path).then(
      (data) => alive && setLoaded({ path, tick, kind: 'ok', data }),
      (cause: unknown) => {
        if (!alive) return;
        const denied = accessDeniedOf(cause);
        setLoaded(denied ? { path, tick, kind: 'denied', denied } : { path, tick, kind: 'failed' });
      },
    );
    return () => {
      alive = false;
    };
  }, [path, tick]);

  // 새로 고침 중에는 이전 표를 그대로 두고(깜빡이지 않게), 조건이 바뀌면 읽는 중으로 그린다
  const current = loaded?.path === path ? loaded : null;
  const refreshing = current !== null && current.tick !== tick;

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-xl font-semibold">백그라운드 작업</h1>
          <p className="mt-1 text-sm text-muted-fg">
            리포트 · 집계 같은 오래 걸리는 작업입니다. 최근 등록한 순서이며, 작업을 누르면 진행
            상황을 볼 수 있습니다.
          </p>
        </div>
        <Button variant="secondary" disabled={refreshing} onClick={() => setTick((n) => n + 1)}>
          {refreshing ? '불러오는 중…' : '새로 고침'}
        </Button>
      </div>

      <JobFilters key={jobsHref(query)} query={query} />

      {!current && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {current?.kind === 'denied' && <AccessNotice denied={current.denied} />}
      {current?.kind === 'failed' && (
        <div role="alert" className="flex items-center gap-4">
          <p className="text-sm text-danger">{LOAD_FAILED}</p>
          <Button variant="secondary" onClick={() => setTick((n) => n + 1)}>
            다시 불러오기
          </Button>
        </div>
      )}
      {current?.kind === 'ok' &&
        current.data.contents.length === 0 &&
        (current.data.totalElements > 0 ? (
          <p className="text-sm text-muted-fg">
            이 페이지에는 작업이 없습니다.{' '}
            <Link
              href={jobsHref({ ...query, page: 0 })}
              className="text-fg underline underline-offset-4"
            >
              첫 페이지로
            </Link>
          </p>
        ) : (
          <p className="text-sm text-muted-fg">
            {hasFilter(query) ? '조건에 맞는 작업이 없습니다.' : '아직 등록된 작업이 없습니다.'}
          </p>
        ))}
      {current?.kind === 'ok' && current.data.contents.length > 0 && (
        <>
          <p className="text-sm text-muted-fg">{current.data.totalElements}건</p>
          <JobTable jobs={current.data.contents} />
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
  query: JobQuery;
  page: number;
  disabled: boolean;
  children: string;
}) {
  if (disabled) return <span className="text-muted-fg">{children}</span>;
  return (
    <Link
      href={jobsHref({ ...query, page })}
      className="rounded-md bg-accent px-3 py-1.5 text-accent-fg hover:opacity-90"
    >
      {children}
    </Link>
  );
}
