'use client';

import { apiFetch, type PageResponse } from '@cf/api-client';
import { Button, cx } from '@cf/ui';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import {
  MEMBER_ROLES,
  membersApiPath,
  membersHref,
  ROLE_LABELS,
  roleLabel,
  type MemberListItem,
  type MemberQuery,
  type MemberRole,
} from '@/lib/member/query';

type Loaded =
  | { path: string; kind: 'ok'; data: PageResponse<MemberListItem> }
  | { path: string; kind: 'failed' }
  | { path: string; kind: 'denied'; denied: AccessDenied };

const ROLE_FILTERS: { role: MemberRole | null; label: string }[] = [
  { role: null, label: '전체' },
  ...MEMBER_ROLES.map((role) => ({ role, label: ROLE_LABELS[role] })),
];

const dateTime = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'short',
  timeStyle: 'short',
  timeZone: 'Asia/Seoul',
});

/**
 * 어드민 회원 목록(#241) — 최신 가입순, 조건은 주소(`query`)가 정본이다. 조회는 서버 감사 로그에 남는다.
 * 이메일 · 이름 · 휴대폰은 서버가 마스킹한 값을 그대로 보인다(원문을 볼 길은 두지 않는다).
 */
export function MemberList({ query }: { query: MemberQuery }) {
  const path = membersApiPath(query);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [retry, setRetry] = useState(0);

  // 상태는 응답 콜백에서만 바꾼다. 읽는 중은 `loaded.path !== path` 로 그린다(늦게 온 이전 응답은 버린다)
  useEffect(() => {
    let alive = true;
    apiFetch<PageResponse<MemberListItem>>(path).then(
      (data) => alive && setLoaded({ path, kind: 'ok', data }),
      (cause: unknown) => {
        if (!alive) return;
        const denied = accessDeniedOf(cause);
        setLoaded(denied ? { path, kind: 'denied', denied } : { path, kind: 'failed' });
      },
    );
    return () => {
      alive = false;
    };
  }, [path, retry]);

  const current = loaded?.path === path ? loaded : null;

  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold">회원</h1>
        <p className="mt-1 text-sm text-muted-fg">
          최근 가입한 순서입니다. 이메일 · 이름 · 휴대폰 번호는 일부를 가려 보여 줍니다.
        </p>
      </div>

      {/* 조건이 역할 하나라 고르면 바로 주소로 간다(페이지는 처음으로) */}
      <nav aria-label="역할" className="flex flex-wrap gap-2">
        {ROLE_FILTERS.map(({ role, label }) => {
          const active = query.role === role;
          return (
            <Link
              key={label}
              href={membersHref({ role, page: 0 })}
              // 2페이지 이상이면 링크(첫 페이지)와 지금 주소가 달라 'page' 가 아니라 "선택됨" 이다
              aria-current={active ? 'true' : undefined}
              className={cx(
                'rounded-md px-3 py-1.5 text-sm',
                active ? 'bg-primary font-medium text-primary-fg' : 'bg-muted hover:opacity-90',
              )}
            >
              {label}
            </Link>
          );
        })}
      </nav>

      {!current && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {current?.kind === 'denied' && <AccessNotice denied={current.denied} />}
      {current?.kind === 'failed' && (
        <div role="alert" className="flex items-center gap-4">
          <p className="text-sm text-danger">{LOAD_FAILED}</p>
          <Button variant="secondary" onClick={() => setRetry((n) => n + 1)}>
            다시 불러오기
          </Button>
        </div>
      )}
      {current?.kind === 'ok' &&
        current.data.contents.length === 0 &&
        (current.data.totalElements > 0 ? (
          // 손으로 고친 주소 · 그사이 줄어든 목록 — 회원은 있으니 "없다" 고 하지 않고 돌아갈 길을 준다
          <p className="text-sm text-muted-fg">
            이 페이지에는 회원이 없습니다.{' '}
            <Link
              href={membersHref({ ...query, page: 0 })}
              className="text-fg underline underline-offset-4"
            >
              첫 페이지로
            </Link>
          </p>
        ) : (
          <p className="text-sm text-muted-fg">
            {query.role ? '이 역할의 회원이 없습니다.' : '아직 회원이 없습니다.'}
          </p>
        ))}
      {current?.kind === 'ok' && current.data.contents.length > 0 && (
        <>
          <p className="text-sm text-muted-fg">{current.data.totalElements}명</p>
          {/* sr-only 같은 absolute 요소가 스크롤 상자 밖으로 나가지 않게 relative 를 준다(§9 재고) */}
          <div className="relative overflow-x-auto">
            <table className="w-full min-w-[48rem] text-left text-sm">
              <caption className="sr-only">회원 목록</caption>
              <thead className="text-muted-fg">
                <tr>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    이름
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    이메일
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    휴대폰
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    역할
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    가입일
                  </th>
                  <th scope="col" className="py-2 font-medium">
                    회원 번호
                  </th>
                </tr>
              </thead>
              <tbody>
                {current.data.contents.map((member) => (
                  <tr key={member.memberId} className="border-t border-border">
                    <th scope="row" className="py-3 pr-4 font-medium whitespace-nowrap">
                      {member.name}
                    </th>
                    <td className="max-w-[20rem] truncate py-3 pr-4" title={member.email}>
                      {member.email}
                    </td>
                    <td className="py-3 pr-4 whitespace-nowrap">
                      {member.phone ?? <span className="text-muted-fg">—</span>}
                    </td>
                    <td className="py-3 pr-4 whitespace-nowrap">{roleLabel(member.role)}</td>
                    <td className="py-3 pr-4 whitespace-nowrap text-muted-fg">
                      {dateTime.format(new Date(member.createdAt))}
                    </td>
                    <td className="py-3 whitespace-nowrap text-muted-fg tabular-nums">
                      {member.memberId}
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
  query: MemberQuery;
  page: number;
  disabled: boolean;
  children: string;
}) {
  if (disabled) return <span className="text-muted-fg">{children}</span>;
  return (
    <Link
      href={membersHref({ ...query, page })}
      className="rounded-md bg-accent px-3 py-1.5 text-accent-fg hover:opacity-90"
    >
      {children}
    </Link>
  );
}
