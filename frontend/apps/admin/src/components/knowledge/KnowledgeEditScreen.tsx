'use client';

import { ApiError, apiFetch } from '@cf/api-client';
import { Button, FieldError } from '@cf/ui';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied, type FormErrors } from '@/lib/api-errors';
import { writeFailure } from '@/lib/knowledge/errors';
import { contentFormOf, contentRequest, type ContentForm } from '@/lib/knowledge/form';
import { knowledgeHref } from '@/lib/knowledge/query';
import {
  knowledgePath,
  type KnowledgeDetail,
  type KnowledgeDraftRequest,
  type KnowledgeEditRequest,
} from '@/lib/knowledge/types';
import { ContentFields } from './ContentFields';

type Status =
  | { kind: 'loading' }
  | { kind: 'denied'; denied: AccessDenied }
  | { kind: 'notFound' }
  | { kind: 'loadFailed' }
  /** base — 버전 · 모드를 정하는 최신 상세. 폼은 따로 들고 다시 읽어도 지우지 않는다 */
  | { kind: 'ready'; base: KnowledgeDetail; form: ContentForm };

/**
 * 지식 내용 고치기(#473). 최신 리비전이 초안이면 그 초안을 통째로 바꾸고(`PUT`, 리비전 버전),
 * 승인 · 게시 · 보관이면 그 내용을 복사해 새 초안을 만든다(`POST /revisions`, 문서 버전 — 기존 게시본은 그대로다).
 * 경합(409) · 결과 모름이면 최신 상세를 다시 읽어 버전 · 모드만 바꾸고 입력은 지킨다 — 새 초안이 이미 만들어졌으면
 * 다시 눌러도 같은 초안을 고치게 되어 초안이 두 번 생기지 않는다. 자동으로 다시 보내지 않는다.
 */
export function KnowledgeEditScreen({ documentId }: { documentId: string }) {
  const id = useId();
  const router = useRouter();
  const [status, setStatus] = useState<Status>({ kind: 'loading' });
  const [errors, setErrors] = useState<FormErrors>({});
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const sending = useRef(false);

  const fail = useCallback((cause: unknown) => {
    const denied = accessDeniedOf(cause);
    if (denied) setStatus({ kind: 'denied', denied });
    else if (cause instanceof ApiError && (cause.kind === 'notFound' || cause.status === 400))
      setStatus({ kind: 'notFound' });
    else setStatus({ kind: 'loadFailed' });
  }, []);

  const load = useCallback(() => {
    return apiFetch<KnowledgeDetail>(knowledgePath(documentId)).then(
      (base) => setStatus({ kind: 'ready', base, form: contentFormOf(base) }),
      fail,
    );
  }, [documentId, fail]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 경합 · 결과 모름 뒤 — 입력은 두고 기준 상세만 바꾼다 */
  const rebase = (message: string, form: ContentForm) => {
    setNotice(message);
    return apiFetch<KnowledgeDetail>(knowledgePath(documentId)).then(
      (base) => setStatus({ kind: 'ready', base, form }),
      (cause: unknown) => {
        const denied = accessDeniedOf(cause);
        if (denied) setStatus({ kind: 'denied', denied });
        else setNotice(`${message} ${LOAD_FAILED}`);
      },
    );
  };

  if (status.kind === 'loading') return <p className="text-sm text-muted-fg">불러오는 중…</p>;
  if (status.kind === 'denied') return <AccessNotice denied={status.denied} />;
  if (status.kind === 'notFound')
    return (
      <div role="status" className="flex max-w-xl flex-col gap-3 rounded-md bg-muted px-5 py-6">
        <p className="font-medium">없는 문서입니다.</p>
        <p className="text-sm text-muted-fg">지식 목록에서 다시 찾아 주세요.</p>
      </div>
    );
  if (status.kind === 'loadFailed')
    return (
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
    );

  const { base, form } = status;
  const draft = base.status.code === 'DRAFT';

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (sending.current) return;
    const request = contentRequest(form);
    if (request.kind === 'errors') {
      setErrors(request.errors);
      return;
    }
    sending.current = true;
    setBusy(true);
    setErrors({});
    setNotice(null);
    const path = knowledgePath(base.documentId);
    const sent = draft
      ? apiFetch<KnowledgeDetail>(path, {
          method: 'PUT',
          json: {
            revisionNumber: base.revisionNumber,
            expectedVersion: base.revisionVersion,
            content: request.content,
          } satisfies KnowledgeEditRequest,
        })
      : apiFetch<KnowledgeDetail>(`${path}/revisions`, {
          method: 'POST',
          json: {
            expectedDocumentVersion: base.documentVersion,
            content: request.content,
          } satisfies KnowledgeDraftRequest,
        });
    sent.then(
      // 옮겨 가는 동안 다시 누르지 않게 sending 은 풀지 않는다
      () => router.push(knowledgeHref(base.documentId)),
      (cause: unknown) => {
        const failure = writeFailure(cause);
        const release = () => {
          sending.current = false;
          setBusy(false);
        };
        if (failure.kind === 'access') {
          release();
          setStatus({ kind: 'denied', denied: failure.denied });
        } else if (failure.kind === 'gone') {
          release();
          setStatus({ kind: 'notFound' });
        } else if (failure.kind === 'reload') {
          void rebase(failure.notice, form).finally(release);
        } else {
          release();
          setErrors(failure.errors);
        }
      },
    );
  };

  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <div className="flex flex-col gap-1">
        <Link
          href={knowledgeHref(base.documentId)}
          className="text-sm text-muted-fg underline underline-offset-4"
        >
          지식 상세
        </Link>
        <h1 className="text-xl font-semibold">{draft ? '초안 고치기' : '새 초안 만들기'}</h1>
        <p className="text-sm text-muted-fg">
          {base.type.name} · {base.locale.name} ·{' '}
          {draft
            ? `${base.revisionNumber}번 초안을 고칩니다. 저장해도 승인 · 게시 전에는 상담에 쓰이지 않습니다.`
            : `${base.revisionNumber}번(${base.status.name}) 내용을 복사해 ${base.latestRevision + 1}번 초안을 만듭니다. 지금 게시본은 새 초안을 게시할 때까지 그대로입니다.`}
        </p>
      </div>
      {notice && (
        <p role="alert" className="text-sm text-danger">
          {notice}
        </p>
      )}
      <form noValidate onSubmit={submit}>
        <fieldset disabled={busy} className="flex flex-col gap-5">
          <ContentFields
            form={form}
            errors={errors}
            onChange={(next) => setStatus({ kind: 'ready', base, form: next })}
          />
          <FieldError id={`${id}-form`} messages={errors.form ?? []} />
          <div>
            <Button type="submit">
              {busy ? '저장 중…' : draft ? '초안 저장' : '새 초안 저장'}
            </Button>
          </div>
        </fieldset>
      </form>
    </div>
  );
}
