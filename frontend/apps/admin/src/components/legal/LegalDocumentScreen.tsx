'use client';

import { ApiError, apiFetch, type PageResponse } from '@cf/api-client';
import { Button, cx, FieldError, TextField } from '@cf/ui';
import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied, type FormErrors } from '@/lib/api-errors';
import { writeFailure } from '@/lib/legal/errors';
import {
  draftErrors,
  formOf,
  hasInput,
  isDirty,
  publicStateText,
  publishBlocker,
  type LegalForm,
} from '@/lib/legal/form';
import type { LegalQuery } from '@/lib/legal/query';
import {
  legalPath,
  legalRevisionsPath,
  legalTemplatePath,
  LEGAL_LIST_PATH,
  LIMITS,
  LOCALE_LABELS,
  TYPE_LABELS,
  type LegalDetail,
  type LegalDraftRequest,
  type LegalPublishRequest,
  type LegalRevision,
  type LegalSummary,
  type LegalTemplate,
} from '@/lib/legal/types';
import {
  ConflictCompare,
  LegalPicker,
  PlaceholderNotice,
  PublishConfirm,
  RevisionRow,
  TemplateGuide,
  TextPreview,
} from './LegalParts';

type Load =
  | { kind: 'loading' }
  | { kind: 'denied'; denied: AccessDenied }
  | { kind: 'loadFailed' }
  /** base — 서버의 최신 상세(문서가 아직 없으면 null). 입력(form)은 따로 들고 다시 읽어도 지우지 않는다 */
  | { kind: 'ready'; base: LegalDetail | null };

/** 입력을 덮어쓰기 전 확인 — 시작 템플릿 · 이전 리비전 불러오기 */
type Overwrite = { label: string; form: LegalForm; template: LegalTemplate | null };

/** 화면 위 안내 — 성공은 status, 실패 · 경합은 alert */
type Notice = { tone: 'info' | 'error'; text: string };

const UNSAVED_LEAVE = '저장하지 않은 입력이 사라집니다. 다른 문서로 옮길까요?';

const TEMPLATE_SESSION_EXPIRED =
  '로그인이 만료되어 시작 템플릿을 불러오지 못했습니다. 입력은 그대로 두었습니다. 새 탭에서 다시 로그인해 주세요.';

const control = 'rounded-md border bg-bg px-3 text-fg outline-none focus:ring-2 focus:ring-primary';

/** 상세 읽기. 404(아직 만들지 않은 조합)는 null 이다 */
async function loadDetail(query: LegalQuery): Promise<LegalDetail | null> {
  try {
    return await apiFetch<LegalDetail>(legalPath(query.type, query.locale));
  } catch (cause) {
    if (cause instanceof ApiError && cause.kind === 'notFound') return null;
    throw cause;
  }
}

/**
 * 쇼핑몰 설정 > 약관 · 개인정보처리방침(#570 · BE #569). 유형(TERMS · PRIVACY) × 언어(ko · en) 하나를 편집한다.
 * - 초안 저장(`PUT /draft`)은 새 리비전을 더할 뿐 공개 페이지를 바꾸지 않는다. 게시(`POST /publish`)만 공개한다.
 * - 게시는 저장한 최신 리비전만, 최종 본문 · 공개 언어를 다시 보이고 검토 확인을 받은 뒤 보낸다.
 * - 409 · 결과 모름은 자동으로 다시 보내지 않는다(초안 저장은 멱등이 아니다). 입력은 지키고 최신 상세를 다시 읽어 비교하게 한다.
 */
export function LegalDocumentScreen({ query }: { query: LegalQuery }) {
  const id = useId();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [summaries, setSummaries] = useState<LegalSummary[] | null>(null);
  const [form, setForm] = useState<LegalForm>(formOf(null));
  const [errors, setErrors] = useState<FormErrors>({});
  const [notice, setNotice] = useState<Notice | null>(null);
  const [comparing, setComparing] = useState(false);
  const [preview, setPreview] = useState(false);
  const [template, setTemplate] = useState<LegalTemplate | null>(null);
  const [overwrite, setOverwrite] = useState<Overwrite | null>(null);
  const [publishing, setPublishing] = useState(false);
  const [reviewChecked, setReviewChecked] = useState(false);
  const [pending, setPending] = useState<'save' | 'publish' | 'template' | null>(null);
  const [historyKey, setHistoryKey] = useState(0);
  const sending = useRef(false);

  const fail = useCallback((cause: unknown) => {
    const denied = accessDeniedOf(cause);
    setLoad(denied ? { kind: 'denied', denied } : { kind: 'loadFailed' });
  }, []);

  const refreshSummaries = useCallback(() => {
    // 목록은 고르기 칸의 상태 표시용이다 — 실패해도 편집은 막지 않는다
    apiFetch<LegalSummary[]>(LEGAL_LIST_PATH).then(setSummaries, () => setSummaries(null));
  }, []);

  const start = useCallback(() => {
    loadDetail(query).then((base) => {
      setLoad({ kind: 'ready', base });
      setForm(formOf(base));
    }, fail);
    refreshSummaries();
  }, [query, fail, refreshSummaries]);

  useEffect(() => {
    start();
  }, [start]);

  const base = load.kind === 'ready' ? load.base : null;
  const dirty = load.kind === 'ready' && isDirty(form, base);

  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  if (load.kind === 'loading') return <p className="text-sm text-muted-fg">불러오는 중…</p>;
  if (load.kind === 'denied') return <AccessNotice denied={load.denied} />;
  if (load.kind === 'loadFailed')
    return (
      <div role="alert" className="flex items-center gap-4">
        <p className="text-sm text-danger">{LOAD_FAILED}</p>
        <Button
          variant="secondary"
          onClick={() => {
            setLoad({ kind: 'loading' });
            start();
          }}
        >
          다시 불러오기
        </Button>
      </div>
    );

  const begin = (kind: 'save' | 'publish' | 'template') => {
    if (sending.current) return false;
    sending.current = true;
    setPending(kind);
    return true;
  };
  const release = () => {
    sending.current = false;
    setPending(null);
  };

  /** 쓰기 성공 — 응답이 곧 최신 상세다 */
  const applied = (next: LegalDetail, message: string) => {
    setLoad({ kind: 'ready', base: next });
    setForm(formOf(next));
    setErrors({});
    setComparing(false);
    setNotice({ tone: 'info', text: message });
    setHistoryKey((key) => key + 1);
    refreshSummaries();
  };

  /** 경합 · 결과 모름 — 입력은 두고 기준 상세만 바꾼다 */
  const rebase = (message: string, conflict: boolean) => {
    setNotice({ tone: 'error', text: message });
    setHistoryKey((key) => key + 1);
    refreshSummaries();
    return loadDetail(query).then(
      (next) => {
        setLoad({ kind: 'ready', base: next });
        setComparing(conflict);
      },
      (cause: unknown) => {
        const denied = accessDeniedOf(cause);
        if (denied) setLoad({ kind: 'denied', denied });
        else setNotice({ tone: 'error', text: `${message} ${LOAD_FAILED}` });
      },
    );
  };

  const writeFailed = (cause: unknown) => {
    const failure = writeFailure(cause);
    if (failure.kind === 'access') {
      release();
      setLoad({ kind: 'denied', denied: failure.denied });
    } else if (failure.kind === 'reload') {
      setPublishing(false);
      void rebase(failure.notice, failure.conflict).finally(release);
    } else {
      release();
      setErrors(failure.errors);
    }
  };

  const save = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const invalid = draftErrors(form);
    if (Object.keys(invalid).length > 0) {
      setErrors({ ...invalid, form: ['입력값을 확인해 주세요.'] });
      return;
    }
    if (!begin('save')) return;
    setErrors({});
    setNotice(null);
    apiFetch<LegalDetail>(`${legalPath(query.type, query.locale)}/draft`, {
      method: 'PUT',
      json: {
        expectedVersion: base?.document.version ?? null,
        title: form.title.trim(),
        body: form.body,
      } satisfies LegalDraftRequest,
    }).then((next) => {
      release();
      applied(
        next,
        `${next.revision.revisionNumber}번 초안을 저장했습니다. 게시하기 전까지 고객에게 보이지 않습니다.`,
      );
    }, writeFailed);
  };

  const publish = () => {
    if (!base || !reviewChecked) {
      setErrors({ review: ['게시 전 검토 확인에 체크해 주세요.'] });
      return;
    }
    if (!begin('publish')) return;
    setErrors({});
    setNotice(null);
    const revisionNumber = base.revision.revisionNumber;
    apiFetch<LegalDetail>(`${legalPath(query.type, query.locale)}/publish`, {
      method: 'POST',
      json: {
        expectedVersion: base.document.version,
        revisionNumber,
        reviewConfirmed: true,
      } satisfies LegalPublishRequest,
    }).then((next) => {
      release();
      setPublishing(false);
      setReviewChecked(false);
      applied(
        next,
        `${revisionNumber}번을 게시했습니다. 스토어프론트 ${LOCALE_LABELS[query.locale]} 페이지에 바로 반영됩니다.`,
      );
    }, writeFailed);
  };

  /** 덮어쓰기 — 입력이 비었으면 바로, 아니면 한 번 묻는다 */
  const offer = (next: Overwrite) => {
    if (!hasInput(form) || (next.form.title === form.title && next.form.body === form.body)) {
      setForm(next.form);
      if (next.template) setTemplate(next.template);
      setErrors({});
    } else setOverwrite(next);
  };

  const loadTemplate = () => {
    if (!begin('template')) return;
    setNotice(null);
    apiFetch<LegalTemplate>(legalTemplatePath(query.type, query.locale)).then(
      (found) => {
        release();
        offer({
          label: '시작 템플릿',
          form: { title: found.title, body: found.body },
          template: found,
        });
      },
      (cause: unknown) => {
        release();
        // 편집 중일 수 있어 로그인 화면으로 보내지 않는다 — 입력을 지키고 안내만 한다
        const denied = accessDeniedOf(cause);
        if (denied === 'forbidden') setLoad({ kind: 'denied', denied });
        else
          setNotice({
            tone: 'error',
            text:
              denied === 'unauthorized' ? TEMPLATE_SESSION_EXPIRED : `시작 템플릿을 ${LOAD_FAILED}`,
          });
      },
    );
  };

  const blocker = publishBlocker(form, base);
  const busy = pending !== null;
  const titleLabel = `${TYPE_LABELS[query.type]} · ${LOCALE_LABELS[query.locale]}`;

  return (
    <div className="flex max-w-5xl flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h1 className="text-xl font-semibold">약관 · 개인정보처리방침</h1>
        <p className="text-sm text-muted-fg">
          초안 저장은 고객에게 공개되지 않습니다. <strong>게시</strong>해야 스토어프론트 공개
          페이지가 바뀝니다. 언어마다 따로 게시하며 다른 언어로 대신 보이지 않습니다.
        </p>
      </div>

      <LegalPicker
        current={query}
        summaries={summaries}
        onNavigate={() => !dirty || window.confirm(UNSAVED_LEAVE)}
      />

      <section aria-labelledby={`${id}-state`} className="flex flex-col gap-1">
        <h2 id={`${id}-state`} className="font-semibold">
          {titleLabel}
        </h2>
        <p role="status" className="text-sm">
          {publicStateText(base?.document ?? null)}
          {dirty && ' 지금 편집기에 저장하지 않은 입력이 있습니다.'}
        </p>
      </section>

      {notice && (
        <p
          role={notice.tone === 'error' ? 'alert' : 'status'}
          className={cx('text-sm', notice.tone === 'error' ? 'text-danger' : 'text-fg')}
        >
          {notice.text}
        </p>
      )}

      {comparing && (
        <ConflictCompare
          mine={form}
          latest={base?.revision ?? null}
          onTakeLatest={() => {
            setForm(formOf(base));
            setComparing(false);
            setNotice(null);
          }}
        />
      )}

      {overwrite && (
        <div
          role="alertdialog"
          aria-labelledby={`${id}-overwrite`}
          className="flex flex-col gap-3 rounded-md border border-primary px-4 py-3"
        >
          <p id={`${id}-overwrite`} className="text-sm font-medium">
            지금 편집기의 제목 · 본문을 {overwrite.label}(으)로 바꿀까요? 저장하지 않은 입력은
            사라집니다.
          </p>
          <div className="flex gap-2">
            <Button
              onClick={() => {
                setForm(overwrite.form);
                if (overwrite.template) setTemplate(overwrite.template);
                setOverwrite(null);
                setErrors({});
              }}
            >
              바꾸기
            </Button>
            <Button variant="secondary" onClick={() => setOverwrite(null)}>
              그대로 두기
            </Button>
          </div>
        </div>
      )}

      <form noValidate onSubmit={save} className="flex flex-col gap-4">
        <fieldset disabled={busy || publishing} className="flex flex-col gap-4">
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="secondary" onClick={loadTemplate}>
              {pending === 'template' ? '불러오는 중…' : '시작 템플릿 불러오기'}
            </Button>
            <Button variant="secondary" onClick={() => setPreview((value) => !value)}>
              {preview ? '편집기로' : '텍스트 미리보기'}
            </Button>
          </div>
          {template && <TemplateGuide template={template} />}
          {preview ? (
            <TextPreview form={form} />
          ) : (
            <>
              <TextField
                label="제목"
                value={form.title}
                errors={errors.title}
                maxLength={LIMITS.title}
                onChange={(event) => setForm({ ...form, title: event.currentTarget.value })}
              />
              <BodyField
                id={`${id}-body`}
                value={form.body}
                errors={errors.body ?? []}
                onChange={(body) => setForm({ ...form, body })}
              />
            </>
          )}
          <PlaceholderNotice form={form} />
          <FieldError messages={publishing ? [] : (errors.form ?? [])} />
          <div className="flex flex-col gap-1">
            <div>
              <Button type="submit" disabled={!dirty}>
                {pending === 'save' ? '저장 중…' : '초안 저장 (공개 안 됨)'}
              </Button>
            </div>
            <p className="text-xs text-muted-fg">
              저장할 때마다 새 리비전이 이력에 쌓입니다. 공개 페이지는 게시할 때만 바뀝니다.
            </p>
          </div>
        </fieldset>
      </form>

      {base && (
        <section aria-label="게시" className="flex flex-col gap-2 border-t border-border pt-6">
          {blocker.kind === 'published' && (
            <p className="text-sm">최신 {base.revision.revisionNumber}번이 이미 공개 중입니다.</p>
          )}
          {blocker.kind === 'blocked' && <p className="text-sm text-muted-fg">{blocker.reason}</p>}
          {blocker.kind === 'ready' && !publishing && (
            <div>
              <Button
                onClick={() => {
                  setPublishing(true);
                  setReviewChecked(false);
                  setErrors({});
                }}
              >
                {base.revision.revisionNumber}번 게시하기…
              </Button>
            </div>
          )}
          {blocker.kind === 'ready' && publishing && (
            <>
              <PublishConfirm
                type={query.type}
                locale={query.locale}
                revision={base.revision}
                checked={reviewChecked}
                pending={pending === 'publish'}
                errors={errors.review ?? []}
                onCheck={setReviewChecked}
                onCancel={() => setPublishing(false)}
                onPublish={publish}
              />
              <FieldError messages={errors.form ?? []} />
            </>
          )}
        </section>
      )}

      {base && (
        <RevisionHistory
          key={historyKey}
          query={query}
          latest={base.document.latestRevision}
          published={base.document.publishedRevision}
          onRestore={(revision) =>
            offer({
              label: `${revision.revisionNumber}번 내용`,
              form: { title: revision.title, body: revision.body },
              template: null,
            })
          }
        />
      )}
    </div>
  );
}

function BodyField({
  id,
  value,
  errors,
  onChange,
}: {
  id: string;
  value: string;
  errors: string[];
  onChange: (value: string) => void;
}) {
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-sm font-medium">
        본문
      </label>
      <textarea
        id={id}
        rows={20}
        value={value}
        aria-invalid={errors.length > 0 || undefined}
        aria-describedby={`${id}-hint${errors.length > 0 ? ` ${id}-error` : ''}`}
        onChange={(event) => onChange(event.currentTarget.value)}
        className={cx(
          control,
          'py-2 font-mono text-sm',
          errors.length > 0 ? 'border-danger' : 'border-border',
        )}
      />
      <p id={`${id}-hint`} className="text-xs text-muted-fg">
        글자 그대로 저장되고 공개 페이지에도 글자 그대로 보입니다(HTML 은 해석하지 않습니다).{' '}
        {value.length.toLocaleString('ko-KR')} / {LIMITS.body.toLocaleString('ko-KR')}자
      </p>
      <FieldError id={`${id}-error`} messages={errors} />
    </div>
  );
}

/** 리비전 이력 — 번호 내림차순 10개씩. 공개 중 · 최신을 표시하고, 지난 본문은 편집기로 불러와 새 초안으로 저장한다 */
function RevisionHistory({
  query,
  latest,
  published,
  onRestore,
}: {
  query: LegalQuery;
  latest: number;
  published: number | null;
  onRestore: (revision: LegalRevision) => void;
}) {
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<PageResponse<LegalRevision> | 'failed' | null>(null);

  useEffect(() => {
    let alive = true;
    apiFetch<PageResponse<LegalRevision>>(legalRevisionsPath(query.type, query.locale, page)).then(
      (next) => alive && setResult(next),
      () => alive && setResult('failed'),
    );
    return () => {
      alive = false;
    };
  }, [query, page]);

  return (
    <section aria-label="리비전 이력" className="flex flex-col gap-2 border-t border-border pt-6">
      <h2 className="font-semibold">리비전 이력</h2>
      <p className="text-xs text-muted-fg">
        지난 게시본을 다시 공개하려면 편집기로 불러와 새 초안으로 저장한 뒤 게시합니다. 고객은 지난
        게시본을 공개 페이지에서 번호로 볼 수 있고, 게시하지 않은 초안은 볼 수 없습니다.
      </p>
      {result === null && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {result === 'failed' && <p className="text-sm text-danger">이력을 {LOAD_FAILED}</p>}
      {result !== null && result !== 'failed' && (
        <>
          <ul>
            {result.contents.map((revision) => (
              <RevisionRow
                key={revision.revisionNumber}
                revision={revision}
                latest={revision.revisionNumber === latest}
                published={revision.revisionNumber === published}
                onRestore={onRestore}
              />
            ))}
          </ul>
          {result.totalPages > 1 && (
            <div className="flex items-center gap-3 text-sm">
              <Button
                variant="secondary"
                disabled={page === 0}
                onClick={() => setPage((value) => value - 1)}
              >
                이전
              </Button>
              <span>
                {page + 1} / {result.totalPages}
              </span>
              <Button
                variant="secondary"
                disabled={page + 1 >= result.totalPages}
                onClick={() => setPage((value) => value + 1)}
              >
                다음
              </Button>
            </div>
          )}
        </>
      )}
    </section>
  );
}
