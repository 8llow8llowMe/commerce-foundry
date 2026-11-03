'use client';

import { ApiError, apiFetch } from '@cf/api-client';
import { Button } from '@cf/ui';
import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import { formatDateTime, periodLabel } from '@/lib/display/text';
import {
  approveRequest,
  archiveQuery,
  knowledgeActions,
  publishRequest,
} from '@/lib/knowledge/actions';
import { writeFailure } from '@/lib/knowledge/errors';
import { KNOWLEDGE_HREF, knowledgeEditHref } from '@/lib/knowledge/query';
import { knowledgePath, type KnowledgeDetail } from '@/lib/knowledge/types';

type Status =
  | { kind: 'loading' }
  | { kind: 'denied'; denied: AccessDenied }
  | { kind: 'notFound' }
  | { kind: 'loadFailed' }
  | { kind: 'ready'; detail: KnowledgeDetail };

type Notice = { tone: 'ok' | 'warn'; message: string } | null;
type Action = 'approve' | 'publish' | 'archive';

const DONE: Record<Action, string> = {
  approve: '승인했습니다. 게시하면 상담에 쓰입니다.',
  publish: '게시했습니다. 이전 게시본은 보관되었습니다.',
  archive: '보관했습니다.',
};

/**
 * 지식 상세(#473) — `GET /api/v1/admin/support/knowledge/{id}`. 최신 리비전 내용과 승인 · 게시 · 보관 버튼을 둔다.
 * 쓰기는 응답 · 상세에 실린 문서/리비전 버전을 그대로 보낸다(낙관적 잠금). 경합(409) · 상태 전이 거절 · 결과 모름은
 * 최신 상세를 다시 읽고 안내한다 — 같은 요청을 자동으로 다시 보내지 않는다. 요청 중에는 버튼을 모두 막는다(더블 클릭).
 */
export function KnowledgeDetailScreen({ documentId }: { documentId: string }) {
  const [status, setStatus] = useState<Status>({ kind: 'loading' });
  const [notice, setNotice] = useState<Notice>(null);
  const [pending, setPending] = useState<Action | null>(null);
  const [confirming, setConfirming] = useState<Action | null>(null);
  /** 마지막으로 보낸 읽기 — 늦게 온 이전 응답이 최신을 덮지 않게 */
  const latest = useRef(0);
  const sending = useRef(false);
  const heading = useRef<HTMLHeadingElement>(null);

  // 작업 뒤 누른 버튼이 사라진다 — 포커스를 상태 제목으로 옮겨 안내가 이어 읽히게 한다
  useEffect(() => {
    if (notice) heading.current?.focus();
  }, [notice]);

  const fail = useCallback((cause: unknown) => {
    const denied = accessDeniedOf(cause);
    if (denied) setStatus({ kind: 'denied', denied });
    else if (cause instanceof ApiError && (cause.kind === 'notFound' || cause.status === 400))
      setStatus({ kind: 'notFound' });
    else setStatus({ kind: 'loadFailed' });
  }, []);

  const load = useCallback(() => {
    const request = ++latest.current;
    return apiFetch<KnowledgeDetail>(knowledgePath(documentId)).then(
      (detail) => request === latest.current && setStatus({ kind: 'ready', detail }),
      (cause: unknown) => request === latest.current && fail(cause),
    );
  }, [documentId, fail]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 다시 읽고 안내를 남긴다. 다시 읽기가 실패하면 그 화면 그대로 안내에 덧붙인다 */
  const reloadWith = (next: Notice) => {
    setNotice(next);
    const request = ++latest.current;
    apiFetch<KnowledgeDetail>(knowledgePath(documentId)).then(
      (detail) => request === latest.current && setStatus({ kind: 'ready', detail }),
      (cause: unknown) => {
        if (request !== latest.current) return;
        const denied = accessDeniedOf(cause);
        if (denied) setStatus({ kind: 'denied', denied });
        else if (cause instanceof ApiError && cause.kind === 'notFound')
          setStatus({ kind: 'notFound' });
        else if (next) setNotice({ tone: 'warn', message: `${next.message} ${LOAD_FAILED}` });
      },
    );
  };

  const run = (action: Action, detail: KnowledgeDetail) => {
    if (sending.current) return;
    sending.current = true;
    setPending(action);
    setConfirming(null);
    setNotice(null);
    const path = knowledgePath(detail.documentId);
    const request: Promise<KnowledgeDetail | null> =
      action === 'archive'
        ? apiFetch<null>(`${path}?${archiveQuery(detail)}`, { method: 'DELETE' })
        : apiFetch<KnowledgeDetail>(`${path}/${action}`, {
            method: 'POST',
            json: action === 'approve' ? approveRequest(detail) : publishRequest(detail),
          });
    request
      .then(
        (changed) => {
          if (changed) {
            latest.current += 1;
            setStatus({ kind: 'ready', detail: changed });
            setNotice({ tone: 'ok', message: DONE[action] });
          } else {
            // 보관은 본문이 없다 — 바뀐 상태 · 게시 포인터를 서버 값으로 다시 읽는다
            reloadWith({ tone: 'ok', message: DONE[action] });
          }
        },
        (cause: unknown) => {
          const failure = writeFailure(cause);
          if (failure.kind === 'access') setStatus({ kind: 'denied', denied: failure.denied });
          else if (failure.kind === 'gone') setStatus({ kind: 'notFound' });
          else if (failure.kind === 'reload') reloadWith({ tone: 'warn', message: failure.notice });
          else
            setNotice({
              tone: 'warn',
              message: Object.values(failure.errors).flat()[0] ?? LOAD_FAILED,
            });
        },
      )
      .finally(() => {
        sending.current = false;
        setPending(null);
      });
  };

  const detail = status.kind === 'ready' ? status.detail : null;

  return (
    <div className="flex max-w-4xl flex-col gap-6">
      <div className="flex flex-col gap-1">
        <Link href={KNOWLEDGE_HREF} className="text-sm text-muted-fg underline underline-offset-4">
          지식 목록
        </Link>
        <h1 className="text-xl font-semibold">{detail ? detail.title : '지식 상세'}</h1>
        {detail && (
          <p className="text-sm text-muted-fg">
            {detail.type.name} · {detail.locale.name} · {detail.category.name}
          </p>
        )}
      </div>

      {status.kind === 'loading' && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {status.kind === 'denied' && <AccessNotice denied={status.denied} />}
      {status.kind === 'notFound' && (
        <div role="status" className="flex max-w-xl flex-col gap-3 rounded-md bg-muted px-5 py-6">
          <p className="font-medium">없는 문서입니다.</p>
          <p className="text-sm text-muted-fg">
            주소가 맞는지 확인하거나 지식 목록에서 다시 찾아 주세요.
          </p>
        </div>
      )}
      {status.kind === 'loadFailed' && (
        <div role="alert" className="flex items-center gap-4">
          <p className="text-sm text-danger">{LOAD_FAILED}</p>
          <Button
            variant="secondary"
            onClick={() => {
              setStatus({ kind: 'loading' });
              void load();
            }}
          >
            다시 불러오기
          </Button>
        </div>
      )}

      {detail && (
        <>
          <section
            aria-labelledby="knowledge-status"
            className="flex flex-col gap-3 rounded-md border border-border p-4"
          >
            <h2
              id="knowledge-status"
              ref={heading}
              tabIndex={-1}
              className="font-medium outline-none"
            >
              {detail.revisionNumber}번 리비전 ·{' '}
              <span title={detail.status.description}>{detail.status.name}</span>
            </h2>
            <RevisionFacts detail={detail} />
            {notice && (
              <p
                role={notice.tone === 'warn' ? 'alert' : 'status'}
                className={notice.tone === 'warn' ? 'text-sm text-danger' : 'text-sm'}
              >
                {notice.message}
              </p>
            )}
            <ActionBar
              // 리비전 · 상태가 바뀌면 확인 단계를 새로 시작한다
              key={`${detail.revisionNumber}-${detail.status.code}-${detail.revisionVersion}`}
              detail={detail}
              pending={pending}
              confirming={confirming}
              onConfirm={setConfirming}
              onRun={(action) => run(action, detail)}
            />
          </section>
          <section aria-labelledby="knowledge-body" className="flex flex-col gap-2">
            <h2 id="knowledge-body" className="font-medium">
              본문
            </h2>
            {/* plain text — React 가 이스케이프한다. 줄바꿈은 그대로 보인다 */}
            <p className="whitespace-pre-wrap break-words rounded-md bg-muted/50 p-4 text-sm">
              {detail.body}
            </p>
          </section>
        </>
      )}
    </div>
  );
}

/** 상태 줄 아래 사실들 — 기간 · 승인 · 게시. 관리자 아이디 원문은 보이지 않는다(#305) */
export function RevisionFacts({ detail }: { detail: KnowledgeDetail }) {
  const otherPublished =
    detail.publishedRevision !== null && detail.publishedRevision !== detail.revisionNumber;
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
      <dt className="text-muted-fg">유효기간</dt>
      <dd>{periodLabel(detail.validFrom, detail.validTo)}</dd>
      <dt className="text-muted-fg">승인</dt>
      <dd>{detail.approvedAt ? formatDateTime(detail.approvedAt) : '—'}</dd>
      <dt className="text-muted-fg">게시</dt>
      <dd>
        {detail.publishedRevision === null
          ? '게시본 없음'
          : otherPublished
            ? `${detail.publishedRevision}번 리비전이 게시 중입니다. 이 리비전을 게시하면 바뀝니다.`
            : detail.publishedAt
              ? `${formatDateTime(detail.publishedAt)} 게시`
              : '게시 중'}
      </dd>
    </dl>
  );
}

export function ActionBar({
  detail,
  pending,
  confirming,
  onConfirm,
  onRun,
  now: fixedNow,
}: {
  detail: KnowledgeDetail;
  pending: Action | null;
  confirming: Action | null;
  onConfirm: (action: Action | null) => void;
  onRun: (action: Action) => void;
  /** 테스트용 고정 시각. 없으면 그린 시각(부모가 리비전 · 상태가 바뀌면 key 로 새로 그린다) */
  now?: Date;
}) {
  const [mounted] = useState(() => new Date());
  const actions = knowledgeActions(detail, fixedNow ?? mounted);
  const busy = pending !== null;

  if (confirming === 'publish' || confirming === 'archive') {
    const message =
      confirming === 'publish'
        ? detail.publishedRevision !== null
          ? `${detail.revisionNumber}번 리비전을 게시합니다. 지금 게시 중인 ${detail.publishedRevision}번은 보관됩니다.`
          : `${detail.revisionNumber}번 리비전을 게시합니다. 게시하면 상담에 바로 쓰입니다.`
        : actions.archive === 'unpublish'
          ? '게시 중인 리비전을 보관합니다. 게시가 해제되어 상담에 더는 쓰이지 않습니다.'
          : `${detail.revisionNumber}번 리비전을 보관합니다. 보관한 리비전은 다시 쓸 수 없고, 고치려면 새 초안을 만듭니다.`;
    return (
      <div
        role="group"
        aria-label="작업 확인"
        className="flex flex-col gap-3 rounded-md bg-muted p-3"
      >
        <p className="text-sm">{message}</p>
        <div className="flex gap-3">
          <Button disabled={busy} onClick={() => onRun(confirming)}>
            {busy ? '처리 중…' : confirming === 'publish' ? '게시' : '보관'}
          </Button>
          <Button variant="secondary" disabled={busy} onClick={() => onConfirm(null)}>
            취소
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-3">
        {busy ? (
          <span className="text-sm text-muted-fg">처리 중…</span>
        ) : (
          <Link
            href={knowledgeEditHref(detail.documentId)}
            className="inline-flex h-10 items-center rounded-md border border-border px-4 text-sm hover:bg-muted"
          >
            {actions.edit === 'draft' ? '초안 고치기' : '새 초안 만들기'}
          </Link>
        )}
        {actions.approve && (
          <Button disabled={busy} onClick={() => onRun('approve')}>
            {pending === 'approve' ? '승인 중…' : '승인'}
          </Button>
        )}
        {actions.publish && (
          <Button disabled={busy} onClick={() => onConfirm('publish')}>
            게시
          </Button>
        )}
        {actions.archive && (
          <Button variant="secondary" disabled={busy} onClick={() => onConfirm('archive')}>
            {actions.archive === 'unpublish' ? '게시 해제(보관)' : '보관'}
          </Button>
        )}
      </div>
      {actions.publishBlocked && <p className="text-sm text-muted-fg">{actions.publishBlocked}</p>}
    </div>
  );
}
