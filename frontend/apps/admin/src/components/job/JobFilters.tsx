'use client';

import { Button, TextField } from '@cf/ui';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useId, useState, type FormEvent } from 'react';
import {
  DEFAULT_QUERY,
  hasFilter,
  JOB_TYPE_FORMAT_ERROR,
  JOB_TYPE_MAX,
  jobsHref,
  normalizeJobType,
  type JobQuery,
} from '@/lib/job/query';
import { JOB_STATUS_LABELS, JOB_STATUSES } from '@/lib/job/types';

/**
 * 작업 목록 조건(#272) — 상태 · 종류. 고친 조건은 "검색" 을 눌러야 주소로 간다(주소가 조건의 정본이다).
 * 부모가 `key` 로 지금 주소를 넘겨, 주소가 바뀌면(뒤로 가기 · 페이지 이동) 폼을 새로 시작한다.
 */
export function JobFilters({ query }: { query: JobQuery }) {
  const id = useId();
  const router = useRouter();
  const [status, setStatus] = useState(query.status ?? '');
  const [type, setType] = useState(query.type ?? '');
  const [typeErrors, setTypeErrors] = useState<string[]>([]);

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const normalized = normalizeJobType(type);
    if (!normalized.ok) {
      setTypeErrors([JOB_TYPE_FORMAT_ERROR]);
      return;
    }
    setTypeErrors([]);
    router.push(
      jobsHref({
        status: JOB_STATUSES.find((item) => item === status) ?? null,
        type: normalized.type,
        page: 0,
      }),
    );
  };

  return (
    <form
      role="search"
      aria-label="작업 조건"
      noValidate
      onSubmit={submit}
      className="flex flex-col gap-4 rounded-md bg-muted/50 p-4"
    >
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div className="flex min-w-0 flex-col gap-1">
          <label htmlFor={`${id}-status`} className="text-sm font-medium">
            상태
          </label>
          <select
            id={`${id}-status`}
            value={status}
            onChange={(event) => setStatus(event.currentTarget.value)}
            className="h-10 min-w-0 rounded-md border border-border bg-bg px-3"
          >
            <option value="">전체</option>
            {JOB_STATUSES.map((value) => (
              <option key={value} value={value}>
                {JOB_STATUS_LABELS[value]}
              </option>
            ))}
          </select>
        </div>
        <TextField
          label="작업 종류 (예: SALES_REPORT_EXPORT)"
          type="search"
          autoCapitalize="characters"
          spellCheck={false}
          maxLength={JOB_TYPE_MAX}
          value={type}
          onChange={(event) => setType(event.currentTarget.value)}
          errors={typeErrors}
        />
      </div>
      <div className="flex items-center gap-4">
        <Button type="submit">검색</Button>
        {hasFilter(query) && (
          <Link
            href={jobsHref(DEFAULT_QUERY)}
            className="text-sm underline-offset-4 hover:underline"
          >
            조건 초기화
          </Link>
        )}
      </div>
    </form>
  );
}
