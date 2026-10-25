'use client';

import { Button } from '@cf/ui';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useId, useState, type FormEvent } from 'react';
import { auditLogsHref, DEFAULT_QUERY, hasFilter, type AuditLogQuery } from '@/lib/audit/query';
import {
  AUDIT_ACTION_LABELS,
  AUDIT_ACTIONS,
  AUDIT_RESULT_LABELS,
  AUDIT_RESULTS,
} from '@/lib/audit/types';

/**
 * 감사 로그 조건(#320 · #329) — 행위 · 결과. 고친 조건은 "검색" 을 눌러야 주소로 간다(주소가 조건의 정본이다).
 * 부모가 `key` 로 지금 주소를 넘겨, 주소가 바뀌면(뒤로 가기 · 페이지 이동) 폼을 새로 시작한다.
 * 관리자 조건은 두지 않는다 — 관리자 이름을 고를 수단이 BE #322 뒤에 생긴다. 대상 조건은 원문을 다뤄 두지 않는다(#305).
 */
export function AuditLogFilters({ query }: { query: AuditLogQuery }) {
  const id = useId();
  const router = useRouter();
  const [action, setAction] = useState<string>(query.action ?? '');
  const [result, setResult] = useState<string>(query.result ?? '');

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    router.push(
      auditLogsHref({
        action: AUDIT_ACTIONS.find((item) => item === action) ?? null,
        result: AUDIT_RESULTS.find((item) => item === result) ?? null,
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
      className="flex flex-col gap-4 rounded-md bg-muted/50 p-4 sm:flex-row sm:items-end"
    >
      <div className="flex min-w-0 flex-col gap-1 sm:w-64">
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
      <div className="flex min-w-0 flex-col gap-1 sm:w-40">
        <label htmlFor={`${id}-result`} className="text-sm font-medium">
          결과
        </label>
        <select
          id={`${id}-result`}
          value={result}
          onChange={(event) => setResult(event.currentTarget.value)}
          className="h-10 min-w-0 rounded-md border border-border bg-bg px-3"
        >
          <option value="">전체</option>
          {AUDIT_RESULTS.map((value) => (
            <option key={value} value={value}>
              {AUDIT_RESULT_LABELS[value]}
            </option>
          ))}
        </select>
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
