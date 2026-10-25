'use client';

import { apiFetch, type PageResponse } from '@cf/api-client';
import { Button, cx } from '@cf/ui';
import { useEffect, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import {
  ADMIN_LABEL,
  compareHistory,
  historyLoadFailure,
  historyPagePath,
  type HistoryLine,
} from '@/lib/shipping/history';
import type { DomesticPolicyHistoryItem } from '@/lib/shipping/types';

const dateTime = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'medium',
  timeStyle: 'short',
  timeZone: 'Asia/Seoul',
});

type Loaded =
  | { page: number; kind: 'ok'; data: PageResponse<DomesticPolicyHistoryItem> }
  | { page: number; kind: 'failed' }
  | { page: number; kind: 'denied'; denied: AccessDenied };

/**
 * 국내 배송비 정책 변경 이력(#319) — `GET /api/v1/admin/shipping-policies/domestic/histories`, 최신순 페이지.
 * 정책을 저장하면 편집 화면이 `key` 를 바꿔 첫 페이지부터 다시 읽게 한다.
 */
export function DomesticPolicyHistory() {
  const [page, setPage] = useState(0);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [retry, setRetry] = useState(0);

  // 상태는 응답 콜백에서만 바꾼다. 읽는 중은 `loaded.page !== page` 로 그린다
  useEffect(() => {
    let alive = true;
    apiFetch<PageResponse<DomesticPolicyHistoryItem>>(historyPagePath(page)).then(
      (data) => alive && setLoaded({ page, kind: 'ok', data }),
      (cause: unknown) => alive && setLoaded({ page, ...historyLoadFailure(cause) }),
    );
    return () => {
      alive = false;
    };
  }, [page, retry]);

  const current = loaded?.page === page ? loaded : null;

  return (
    <section
      aria-labelledby="policy-history-title"
      className="flex flex-col gap-4 rounded-md bg-muted/50 p-5"
    >
      <div>
        <h2 id="policy-history-title" className="font-semibold">
          변경 이력
        </h2>
        <p className="mt-1 text-sm text-muted-fg">
          저장할 때마다 한 건씩 남습니다. 바뀐 칸을 굵게 표시합니다.
        </p>
      </div>
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
      {current?.kind === 'ok' && <HistoryPage data={current.data} page={page} onPage={setPage} />}
    </section>
  );
}

/** 한 페이지 — 빈 이력 · 범위를 넘은 페이지 안내, 이력 목록, 페이지 이동 */
export function HistoryPage({
  data,
  page,
  onPage,
}: {
  data: PageResponse<DomesticPolicyHistoryItem>;
  page: number;
  onPage: (page: number) => void;
}) {
  if (data.totalElements === 0)
    return <p className="text-sm text-muted-fg">아직 변경 이력이 없습니다.</p>;
  if (data.contents.length === 0)
    return (
      <div className="flex items-center gap-4 text-sm">
        <p className="text-muted-fg">이 페이지에는 이력이 없습니다.</p>
        <Button variant="secondary" onClick={() => onPage(0)}>
          첫 페이지로
        </Button>
      </div>
    );

  return (
    <>
      <ol className="flex flex-col gap-3">
        {data.contents.map((item) => (
          <li key={item.historyId}>
            <HistoryEntry item={item} />
          </li>
        ))}
      </ol>
      {data.totalPages > 1 && (
        <div className="flex items-center gap-3 text-sm">
          <Button variant="secondary" disabled={page === 0} onClick={() => onPage(page - 1)}>
            이전
          </Button>
          <span>
            {page + 1} / {data.totalPages}
          </span>
          <Button
            variant="secondary"
            disabled={page + 1 >= data.totalPages}
            onClick={() => onPage(page + 1)}
          >
            다음
          </Button>
        </div>
      )}
    </>
  );
}

const KIND_LABEL = { initial: '처음 설정', changed: '변경', unchanged: '값 변경 없음' } as const;

/** 이력 한 건 — 교체 시각 · 교체한 사람 · 칸별 이전 → 이후 값(바뀐 칸 강조) */
export function HistoryEntry({ item }: { item: DomesticPolicyHistoryItem }) {
  const { kind, fields } = compareHistory(item);

  return (
    <article className="flex flex-col gap-3 rounded-md bg-bg p-4">
      <header className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
        <time dateTime={item.changedAt} className="font-medium">
          {dateTime.format(new Date(item.changedAt))}
        </time>
        <span className="text-muted-fg">{ADMIN_LABEL}</span>
        <span className="rounded-md bg-muted px-2 py-0.5 text-xs">{KIND_LABEL[kind]}</span>
      </header>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[32rem] text-left text-sm">
          <thead className="text-muted-fg">
            <tr>
              <th className="py-1 pr-4 font-medium">항목</th>
              {kind !== 'initial' && <th className="py-1 pr-4 font-medium">이전</th>}
              <th className="py-1 font-medium">{kind === 'initial' ? '설정 값' : '이후'}</th>
            </tr>
          </thead>
          <tbody>
            {fields.map((field) => (
              <tr
                key={field.key}
                className={cx('align-top', field.changed && 'bg-accent text-accent-fg')}
              >
                <th scope="row" className="py-1 pr-4 font-medium whitespace-nowrap">
                  {field.label}
                  {field.changed && <span className="sr-only"> (바뀜)</span>}
                </th>
                {field.before && (
                  <td className="py-1 pr-4">
                    <Lines lines={field.before} />
                  </td>
                )}
                <td className="py-1">
                  <Lines lines={field.after} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </article>
  );
}

function Lines({ lines }: { lines: HistoryLine[] }) {
  return (
    <ul className="flex flex-col">
      {lines.map((line, index) => (
        <li key={index} className={cx(line.changed && 'font-semibold')}>
          {line.text}
        </li>
      ))}
    </ul>
  );
}
