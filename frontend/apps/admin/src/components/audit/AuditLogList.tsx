'use client';

import { Button } from '@cf/ui';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { LOAD_FAILED } from '@/lib/api-errors';
import { loadAdminDirectory } from '@/lib/audit/admins';
import { loadAuditLogs, type AuditLogsLoad } from '@/lib/audit/load';
import { auditLogsApiPath, auditLogsHref, hasFilter, type AuditLogQuery } from '@/lib/audit/query';
import { AuditLogFilters, type AdminsState } from './AuditLogFilters';
import { AuditLogTable } from './AuditLogTable';

type Loaded = AuditLogsLoad & { path: string; tick: number };

/**
 * 어드민 관리자 감사 로그(#320, BE #192) — 최신순, 조건(행위 · 관리자 · 페이지)은 주소(`query`)가 정본이다.
 * 관리자 이름은 화면에 들어올 때 관리자 이름표를 한 번 읽어 짝짓는다(`loadAdminDirectory` — 줄마다 읽지 않는다).
 * 이름표를 못 읽어도 감사 로그는 "관리자" 로 보인다.
 */
export function AuditLogList({ query }: { query: AuditLogQuery }) {
  const path = auditLogsApiPath(query);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [tick, setTick] = useState(0);
  const [adminsState, setAdminsState] = useState<AdminsState>({ kind: 'loading' });

  // 이름표는 화면에 머무는 동안 한 번만 읽는다(페이지 · 조건을 바꿔도 다시 읽지 않는다)
  useEffect(() => {
    const controller = new AbortController();
    loadAdminDirectory(controller.signal).then(
      (admins) => setAdminsState({ kind: 'done', admins }),
      () => undefined, // 취소뿐이다
    );
    return () => controller.abort();
  }, []);

  // 상태는 응답 콜백에서만 바꾼다. 읽는 중은 path · tick 이 다른 것으로 그린다(늦게 온 이전 응답은 취소로 버린다)
  useEffect(() => {
    const controller = new AbortController();
    loadAuditLogs(path, controller.signal).then(
      (result) => setLoaded({ ...result, path, tick }),
      () => undefined, // 취소뿐이다
    );
    return () => controller.abort();
  }, [path, tick]);

  // 새로 고침 중에는 이전 표를 그대로 두고(깜빡이지 않게), 조건이 바뀌면 읽는 중으로 그린다
  const current = loaded?.path === path ? loaded : null;
  const refreshing = current !== null && current.tick !== tick;
  const admins = adminsState.kind === 'done' ? adminsState.admins : null;

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-xl font-semibold">관리자 감사 로그</h1>
          <p className="mt-1 text-sm text-muted-fg">
            관리자 로그인 · 회원 목록 조회 같은 관리자 행위 기록입니다. 최근 기록부터 보이며,
            고치거나 지울 수 없습니다.
          </p>
        </div>
        <Button variant="secondary" disabled={refreshing} onClick={() => setTick((n) => n + 1)}>
          {refreshing ? '불러오는 중…' : '새로 고침'}
        </Button>
      </div>

      <AuditLogFilters key={auditLogsHref(query)} query={query} adminsState={adminsState} />

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
            이 페이지에는 기록이 없습니다.{' '}
            <Link
              href={auditLogsHref({ ...query, page: 0 })}
              className="text-fg underline underline-offset-4"
            >
              첫 페이지로
            </Link>
          </p>
        ) : (
          <p className="text-sm text-muted-fg">
            {hasFilter(query) ? '조건에 맞는 기록이 없습니다.' : '아직 기록이 없습니다.'}
          </p>
        ))}
      {current?.kind === 'ok' && current.data.contents.length > 0 && (
        <>
          <p className="text-sm text-muted-fg">{current.data.totalElements}건</p>
          <AuditLogTable logs={current.data.contents} admins={admins} />
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
  query: AuditLogQuery;
  page: number;
  disabled: boolean;
  children: string;
}) {
  if (disabled) return <span className="text-muted-fg">{children}</span>;
  return (
    <Link
      href={auditLogsHref({ ...query, page })}
      className="rounded-md bg-accent px-3 py-1.5 text-accent-fg hover:opacity-90"
    >
      {children}
    </Link>
  );
}
