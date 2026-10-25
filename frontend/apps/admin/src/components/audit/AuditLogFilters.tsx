'use client';

import { Button } from '@cf/ui';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useId, useState, type FormEvent } from 'react';
import { adminOptionLabel, type AdminDirectory } from '@/lib/audit/admins';
import {
  auditLogsHref,
  DEFAULT_QUERY,
  hasFilter,
  isMemberId,
  type AuditLogQuery,
} from '@/lib/audit/query';
import { AUDIT_ACTION_LABELS, AUDIT_ACTIONS } from '@/lib/audit/types';

/** 관리자 이름표 상태 — 읽는 중 · 읽음 · 못 읽음(null) */
export type AdminsState = { kind: 'loading' } | { kind: 'done'; admins: AdminDirectory | null };

/**
 * 감사 로그 조건(#320) — 행위 · 관리자. 고친 조건은 "검색" 을 눌러야 주소로 간다(주소가 조건의 정본이다).
 * 부모가 `key` 로 지금 주소를 넘겨, 주소가 바뀌면(뒤로 가기 · 페이지 이동) 폼을 새로 시작한다.
 * 관리자는 이름표(마스킹한 이름 · 이메일)에서 고른다. 주소의 관리자가 이름표에 없으면 "선택한 관리자" 로 남겨 실제 조건을 보인다.
 */
export function AuditLogFilters({
  query,
  adminsState,
}: {
  query: AuditLogQuery;
  adminsState: AdminsState;
}) {
  const id = useId();
  const router = useRouter();
  const [action, setAction] = useState<string>(query.action ?? '');
  const [actor, setActor] = useState<string>(query.actorMemberId ?? '');

  const admins = adminsState.kind === 'done' ? adminsState.admins : null;
  const options = admins ? [...admins.byId.values()] : [];
  const actorKnown = query.actorMemberId !== null && admins?.byId.has(query.actorMemberId);

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    router.push(
      auditLogsHref({
        action: AUDIT_ACTIONS.find((item) => item === action) ?? null,
        actorMemberId: isMemberId(actor) ? actor : null,
        page: 0,
      }),
    );
  };

  return (
    <form
      role="search"
      aria-label="감사 로그 조건"
      noValidate
      onSubmit={submit}
      className="flex flex-col gap-4 rounded-md bg-muted/50 p-4"
    >
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div className="flex min-w-0 flex-col gap-1">
          <label htmlFor={`${id}-action`} className="text-sm font-medium">
            행위
          </label>
          <select
            id={`${id}-action`}
            value={action}
            onChange={(event) => setAction(event.currentTarget.value)}
            className="h-10 min-w-0 rounded-md border border-border bg-bg px-3"
          >
            <option value="">전체</option>
            {AUDIT_ACTIONS.map((value) => (
              <option key={value} value={value}>
                {AUDIT_ACTION_LABELS[value]}
              </option>
            ))}
          </select>
        </div>
        <div className="flex min-w-0 flex-col gap-1">
          <label htmlFor={`${id}-actor`} className="text-sm font-medium">
            관리자
          </label>
          <select
            id={`${id}-actor`}
            value={actor}
            disabled={adminsState.kind === 'loading'}
            aria-describedby={`${id}-actor-hint`}
            onChange={(event) => setActor(event.currentTarget.value)}
            className="h-10 min-w-0 rounded-md border border-border bg-bg px-3 disabled:opacity-60"
          >
            <option value="">전체</option>
            {query.actorMemberId && !actorKnown && (
              <option value={query.actorMemberId}>선택한 관리자</option>
            )}
            {options.map((admin) => (
              <option key={admin.memberId} value={admin.memberId}>
                {adminOptionLabel(admin)}
              </option>
            ))}
          </select>
          <p id={`${id}-actor-hint`} className="text-xs text-muted-fg">
            {actorHint(adminsState)}
          </p>
        </div>
      </div>
      <div className="flex items-center gap-4">
        <Button type="submit">검색</Button>
        {hasFilter(query) && (
          <Link
            href={auditLogsHref(DEFAULT_QUERY)}
            className="text-sm underline-offset-4 hover:underline"
          >
            조건 초기화
          </Link>
        )}
      </div>
    </form>
  );
}

function actorHint(state: AdminsState): string {
  if (state.kind === 'loading') return '관리자 목록을 불러오는 중입니다.';
  if (state.admins === null) return '관리자 목록을 불러오지 못해 관리자로 거를 수 없습니다.';
  if (!state.admins.complete) return '관리자가 많아 앞의 100명만 고를 수 있습니다.';
  return '이름 · 이메일은 일부를 가려 보여 줍니다.';
}
