'use client';

import { Button } from '@cf/ui';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useId, useState, type FormEvent } from 'react';
import {
  DEFAULT_QUERY,
  hasFilter,
  knowledgeListHref,
  type KnowledgeQuery,
} from '@/lib/knowledge/query';
import {
  KNOWLEDGE_LOCALES,
  KNOWLEDGE_TYPES,
  LOCALE_LABELS,
  TYPE_LABELS,
} from '@/lib/knowledge/types';

/**
 * 지식 조건 — 유형 · 언어. 고친 조건은 "검색" 을 눌러야 주소로 간다(주소가 조건의 정본이다).
 * 부모가 `key` 로 지금 주소를 넘겨, 주소가 바뀌면 폼을 새로 시작한다.
 */
export function KnowledgeFilters({ query }: { query: KnowledgeQuery }) {
  const id = useId();
  const router = useRouter();
  const [type, setType] = useState<string>(query.type ?? '');
  const [locale, setLocale] = useState<string>(query.locale ?? '');

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    router.push(
      knowledgeListHref({
        type: KNOWLEDGE_TYPES.find((item) => item === type) ?? null,
        locale: KNOWLEDGE_LOCALES.find((item) => item === locale) ?? null,
        page: 0,
      }),
    );
  };

  return (
    <form
      role="search"
      aria-label="지식 조건"
      noValidate
      onSubmit={submit}
      className="flex flex-col gap-4 rounded-md bg-muted/50 p-4 sm:flex-row sm:items-end"
    >
      <div className="flex min-w-0 flex-col gap-1 sm:w-44">
        <label htmlFor={`${id}-type`} className="text-sm font-medium">
          유형
        </label>
        <select
          id={`${id}-type`}
          value={type}
          onChange={(event) => setType(event.currentTarget.value)}
          className="h-10 min-w-0 rounded-md border border-border bg-bg px-3"
        >
          <option value="">전체</option>
          {KNOWLEDGE_TYPES.map((value) => (
            <option key={value} value={value}>
              {TYPE_LABELS[value]}
            </option>
          ))}
        </select>
      </div>
      <div className="flex min-w-0 flex-col gap-1 sm:w-36">
        <label htmlFor={`${id}-locale`} className="text-sm font-medium">
          언어
        </label>
        <select
          id={`${id}-locale`}
          value={locale}
          onChange={(event) => setLocale(event.currentTarget.value)}
          className="h-10 min-w-0 rounded-md border border-border bg-bg px-3"
        >
          <option value="">전체</option>
          {KNOWLEDGE_LOCALES.map((value) => (
            <option key={value} value={value}>
              {LOCALE_LABELS[value]}
            </option>
          ))}
        </select>
      </div>
      <div className="flex items-center gap-4">
        <Button type="submit">검색</Button>
        {hasFilter(query) && (
          <Link
            href={knowledgeListHref(DEFAULT_QUERY)}
            className="text-sm underline-offset-4 hover:underline"
          >
            조건 초기화
          </Link>
        )}
      </div>
    </form>
  );
}
