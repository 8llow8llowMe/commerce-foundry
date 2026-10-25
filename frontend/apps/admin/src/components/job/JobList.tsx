'use client';

import { apiFetch, type PageResponse } from '@cf/api-client';
import { Button } from '@cf/ui';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import { isCancelPending, progressText } from '@/lib/job/detail';
import { formatJobTime } from '@/lib/job/format';
import { hasFilter, jobsApiPath, jobsHref, type JobQuery } from '@/lib/job/query';
import type { JobListItem } from '@/lib/job/types';
import { JobFilters } from './JobFilters';

type Loaded =
  | { path: string; tick: number; kind: 'ok'; data: PageResponse<JobListItem> }
  | { path: string; tick: number; kind: 'failed' }
  | { path: string; tick: number; kind: 'denied'; denied: AccessDenied };

/**
 * 어드민 백그라운드 작업 목록(#272) — 등록 최신순, 조건(상태 · 종류 · 페이지)은 주소(`query`)가 정본이다.
 * 상태는 서버가 준 `status.name` 을 보이고 설명은 title 로 둔다. 진행 상황은 상세에서 주기적으로 갱신한다 —
 * 목록은 "새로 고침" 으로만 다시 읽는다.
 */
export function JobList({ query }: { query: JobQuery }) {
  const path = jobsApiPath(query);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [tick, setTick] = useState(0);

  // 상태는 응답 콜백에서만 바꾼다. 읽는 중은 path · tick 이 다른 것으로 그린다(늦게 온 이전 응답은 버린다)
  useEffect(() => {
    let alive = true;
    apiFetch<PageResponse<JobListItem>>(path).then(
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
          {/* sr-only 같은 absolute 요소가 스크롤 상자 밖으로 나가지 않게 relative 를 준다 */}
          <div className="relative overflow-x-auto">
            <table className="w-full min-w-[56rem] text-left text-sm">
              <caption className="sr-only">백그라운드 작업 목록</caption>
              <thead className="text-muted-fg">
                <tr>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    작업 번호
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    종류
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    상태
                  </th>
                  <th scope="col" className="py-2 pr-4 text-right font-medium">
                    진행률
                  </th>
                  <th scope="col" className="py-2 pr-4 text-right font-medium">
                    시도
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    오류 코드
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    예정 시각
                  </th>
                  <th scope="col" className="py-2 font-medium">
                    종료 시각
                  </th>
                </tr>
              </thead>
              <tbody>
                {current.data.contents.map((job) => (
                  <tr key={job.jobId} className="border-t border-border">
                    <th scope="row" className="py-3 pr-4 font-medium whitespace-nowrap">
                      <Link
                        href={`/jobs/${encodeURIComponent(job.jobId)}`}
                        className="tabular-nums underline underline-offset-4"
                      >
                        {job.jobId}
                      </Link>
                    </th>
                    <td className="py-3 pr-4 whitespace-nowrap">{job.type}</td>
                    <td className="py-3 pr-4 whitespace-nowrap">
                      <span title={job.status.description}>{job.status.name}</span>
                      {isCancelPending(job) && (
                        <span className="ml-2 text-xs text-muted-fg">취소 요청됨</span>
                      )}
                    </td>
                    <td className="py-3 pr-4 text-right tabular-nums">
                      {progressText(job.progressPercent)}
                    </td>
                    <td className="py-3 pr-4 text-right whitespace-nowrap tabular-nums">
                      {job.attempt} / {job.maxAttempts}
                    </td>
                    <td className="py-3 pr-4 whitespace-nowrap">
                      {job.errorCode ?? <span className="text-muted-fg">—</span>}
                    </td>
                    <td className="py-3 pr-4 whitespace-nowrap text-muted-fg">
                      {formatJobTime(job.scheduledAt)}
                    </td>
                    <td className="py-3 whitespace-nowrap text-muted-fg">
                      {formatJobTime(job.finishedAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
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
