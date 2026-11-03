'use client';

import { apiFetch, type PageResponse } from '@cf/api-client';
import { Button } from '@cf/ui';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import {
  hasFilter,
  knowledgeApiPath,
  knowledgeHref,
  knowledgeListHref,
  NEW_KNOWLEDGE_HREF,
  type KnowledgeQuery,
} from '@/lib/knowledge/query';
import type { KnowledgeSummary } from '@/lib/knowledge/types';
import { periodLabel } from '@/lib/display/text';
import { KnowledgeFilters } from './KnowledgeFilters';

type Loaded =
  | { path: string; reload: number; kind: 'ok'; data: PageResponse<KnowledgeSummary> }
  | { path: string; reload: number; kind: 'failed' }
  | { path: string; reload: number; kind: 'denied'; denied: AccessDenied };

/**
 * 상담 지식 목록(#473) — `GET /api/v1/admin/support/knowledge`. 조건은 주소(`query`)가 정본이다. 문서 아이디 내림차순(최신 등록 먼저)이다.
 * 줄은 최신 리비전 요약이다(본문 없음). 제목을 누르면 상세에서 승인 · 게시 · 보관한다.
 */
export function KnowledgeList({ query }: { query: KnowledgeQuery }) {
  const path = knowledgeApiPath(query);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let alive = true;
    apiFetch<PageResponse<KnowledgeSummary>>(path).then(
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

  const current = loaded?.path === path && loaded.reload === reload ? loaded : null;

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-xl font-semibold">지식 관리</h1>
          <p className="mt-1 text-sm text-muted-fg">
            상담에 쓰는 FAQ · 정책 · 응답 템플릿을 작성하고, 승인한 뒤 게시합니다. 게시한 내용을
            고치려면 새 초안을 만들어 다시 승인 · 게시합니다.
          </p>
        </div>
        <Link
          href={NEW_KNOWLEDGE_HREF}
          className="inline-flex h-10 items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-fg hover:opacity-90"
        >
          새 문서
        </Link>
      </div>

      <KnowledgeFilters key={knowledgeListHref(query)} query={query} />

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
          <p className="text-sm text-muted-fg">
            이 페이지에는 문서가 없습니다.{' '}
            <Link
              href={knowledgeListHref({ ...query, page: 0 })}
              className="text-fg underline underline-offset-4"
            >
              첫 페이지로
            </Link>
          </p>
        ) : (
          <p className="text-sm text-muted-fg">
            {hasFilter(query) ? '조건에 맞는 문서가 없습니다.' : '아직 문서가 없습니다.'}
          </p>
        ))}
      {current?.kind === 'ok' && current.data.contents.length > 0 && (
        <>
          <p className="text-sm text-muted-fg">{current.data.totalElements}건</p>
          <KnowledgeTable items={current.data.contents} />
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

/** 게시 칸 — 최신 리비전이 게시본이 아니면 게시 중인 번호를 따로 알린다(새 초안을 고치는 동안에도 이전 게시본이 쓰인다) */
export function publishedText(
  item: Pick<KnowledgeSummary, 'latestRevision' | 'publishedRevision'>,
) {
  if (item.publishedRevision === null) return '게시 안 됨';
  if (item.publishedRevision === item.latestRevision) return '게시 중';
  return `${item.publishedRevision}번 게시 중`;
}

export function KnowledgeTable({ items }: { items: readonly KnowledgeSummary[] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[48rem] text-left text-sm">
        <thead className="border-b border-border text-muted-fg">
          <tr>
            <th scope="col" className="py-2 pr-4 font-medium">
              제목
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              유형
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              언어
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              카테고리
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              최신 리비전
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              게시
            </th>
            <th scope="col" className="py-2 font-medium">
              유효기간
            </th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.documentId} className="border-b border-border">
              <td className="max-w-xs py-2 pr-4">
                <Link
                  href={knowledgeHref(item.documentId)}
                  className="line-clamp-2 underline-offset-4 hover:underline"
                >
                  {item.title}
                </Link>
              </td>
              <td className="py-2 pr-4">{item.type.name}</td>
              <td className="py-2 pr-4">{item.locale.name}</td>
              <td className="py-2 pr-4">{item.category.name}</td>
              <td className="py-2 pr-4">
                {item.latestRevision}번 · {item.status.name}
              </td>
              <td className="py-2 pr-4">{publishedText(item)}</td>
              <td className="py-2 text-muted-fg">{periodLabel(item.validFrom, item.validTo)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function PageLink({
  query,
  page,
  disabled,
  children,
}: {
  query: KnowledgeQuery;
  page: number;
  disabled: boolean;
  children: string;
}) {
  if (disabled) return <span className="text-muted-fg">{children}</span>;
  return (
    <Link
      href={knowledgeListHref({ ...query, page })}
      className="rounded-md bg-accent px-3 py-1.5 text-accent-fg hover:opacity-90"
    >
      {children}
    </Link>
  );
}
