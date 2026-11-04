'use client';

import { Button, Checkbox, cx } from '@cf/ui';
import Link from 'next/link';
import type { MouseEvent } from 'react';
import { placeholdersOf, previewParts, type LegalForm } from '@/lib/legal/form';
import { formatLegalTime } from '@/lib/legal/format';
import { legalHref, type LegalQuery } from '@/lib/legal/query';
import {
  LEGAL_LOCALES,
  LEGAL_TYPES,
  LOCALE_LABELS,
  TYPE_LABELS,
  type LegalLocale,
  type LegalRevision,
  type LegalSummary,
  type LegalTemplate,
  type LegalType,
} from '@/lib/legal/types';

// 약관 화면 조각 — 상태 없이 props 로만 그려서 renderToStaticMarkup 으로 확인한다

/** 스토어프론트 공개 주소(언어 접두어 없는 앱 경로). 영문은 언어 설정에 따라 `/en/...` 또는 언어 호스트다 */
export const publicPathOf = (type: LegalType) => (type === 'TERMS' ? '/terms' : '/privacy');

function pickerState(summary: LegalSummary | undefined): string {
  if (!summary) return '없음';
  if (summary.publishedRevision === null) return `초안 ${summary.latestRevision}번 · 미게시`;
  if (summary.publishedRevision === summary.latestRevision)
    return `${summary.publishedRevision}번 공개 중`;
  return `${summary.publishedRevision}번 공개 · ${summary.latestRevision}번 초안`;
}

/**
 * 문서 고르기 — 유형 × 언어 네 칸. `summaries` 가 null 이면(목록 실패) 상태 없이 링크만 둔다.
 * 저장하지 않은 입력이 있으면 옮기기 전에 묻는다(`onNavigate` 가 false 면 이동을 막는다).
 */
export function LegalPicker({
  current,
  summaries,
  onNavigate,
}: {
  current: LegalQuery;
  summaries: LegalSummary[] | null;
  onNavigate?: () => boolean;
}) {
  const guard = (event: MouseEvent<HTMLAnchorElement>) => {
    if (onNavigate && !onNavigate()) event.preventDefault();
  };
  return (
    <nav aria-label="문서 고르기" className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
      {LEGAL_TYPES.flatMap((type) =>
        LEGAL_LOCALES.map((locale) => {
          const active = type === current.type && locale === current.locale;
          const summary = summaries?.find((item) => item.type === type && item.locale === locale);
          return (
            <Link
              key={`${type}-${locale}`}
              href={legalHref({ type, locale })}
              aria-current={active ? 'page' : undefined}
              onClick={guard}
              className={cx(
                'flex flex-col gap-1 rounded-md border px-3 py-2 text-sm',
                active ? 'border-primary bg-muted font-medium' : 'border-border hover:bg-muted',
              )}
            >
              <span>
                {TYPE_LABELS[type]} · {LOCALE_LABELS[locale]}
              </span>
              {summaries && (
                <span className="text-xs font-normal text-muted-fg">{pickerState(summary)}</span>
              )}
            </Link>
          );
        }),
      )}
    </nav>
  );
}

/** 단순 텍스트 미리보기 — HTML 로 해석하지 않는다. `{{...}}` 빈칸만 눈에 띄게 표시한다 */
export function TextPreview({ form }: { form: LegalForm }) {
  return (
    <article className="flex flex-col gap-4 rounded-md border border-border bg-bg px-5 py-4">
      <h3 className="text-lg font-semibold break-words">
        <Highlighted text={form.title || '(제목 없음)'} />
      </h3>
      <div className="text-sm leading-7 break-words whitespace-pre-wrap">
        <Highlighted text={form.body || '(본문 없음)'} />
      </div>
    </article>
  );
}

function Highlighted({ text }: { text: string }) {
  return previewParts(text).map((part, index) =>
    part.placeholder ? (
      <mark key={index} className="rounded-sm bg-accent px-0.5 text-accent-fg">
        {part.text}
      </mark>
    ) : (
      <span key={index}>{part.text}</span>
    ),
  );
}

/** 아직 바꾸지 않은 빈칸 목록. 없으면 그리지 않는다 */
export function PlaceholderNotice({ form }: { form: LegalForm }) {
  const { tokens, strayMarks } = placeholdersOf(form);
  if (tokens.length === 0 && !strayMarks) return null;
  return (
    <div role="status" className="flex flex-col gap-2 rounded-md bg-muted px-4 py-3 text-sm">
      <p className="font-medium">
        바꾸지 않은 빈칸 {tokens.length}개{strayMarks ? ' · 짝이 맞지 않는 {{ 또는 }}' : ''}
      </p>
      {tokens.length > 0 && (
        <ul className="flex flex-wrap gap-2">
          {tokens.map((token) => (
            <li key={token} className="rounded-sm bg-accent px-2 py-0.5 text-accent-fg">
              {token}
            </li>
          ))}
        </ul>
      )}
      <p className="text-xs text-muted-fg">
        빈칸이 남아 있으면 초안으로는 저장되지만 게시할 수 없습니다.
      </p>
    </div>
  );
}

/** 불러온 시작 템플릿의 사용 안내 · 확인 항목. 법률 검토가 끝난 문서로 표현하지 않는다 */
export function TemplateGuide({ template }: { template: LegalTemplate }) {
  return (
    <aside className="flex flex-col gap-2 rounded-md border border-border px-4 py-3 text-sm">
      <p className="font-medium">
        시작 템플릿(질문 · 빈칸 구조, 버전 {template.version}) — 법률 검토를 마친 완성 문서가
        아닙니다.
      </p>
      <p className="whitespace-pre-wrap text-muted-fg">{template.guidance}</p>
      {template.checklist.length > 0 && (
        <>
          <p className="font-medium">게시 전 확인할 것</p>
          <ul className="list-disc pl-5">
            {template.checklist.map((item, index) => (
              <li key={index}>{item}</li>
            ))}
          </ul>
        </>
      )}
      <p className="text-xs text-muted-fg">
        확인 항목은 안내일 뿐 서버가 사실 여부를 판정하지 않습니다. 실제 처리 사실 · 최신 자료 ·
        전문가 검토는 운영자가 확인해야 합니다.
      </p>
    </aside>
  );
}

/**
 * 게시 확인 — 최종 본문 · 공개 언어 · 공개 주소를 다시 보이고 검토 확인을 받는다.
 * 확인 값(`reviewConfirmed`)은 체크해야만 보낼 수 있다.
 */
export function PublishConfirm({
  type,
  locale,
  revision,
  checked,
  pending,
  errors,
  onCheck,
  onCancel,
  onPublish,
}: {
  type: LegalType;
  locale: LegalLocale;
  revision: LegalRevision;
  checked: boolean;
  pending: boolean;
  errors: string[];
  onCheck: (checked: boolean) => void;
  onCancel: () => void;
  onPublish: () => void;
}) {
  return (
    <section
      aria-labelledby="legal-publish-title"
      className="flex flex-col gap-4 rounded-md border border-primary px-5 py-4"
    >
      <div className="flex flex-col gap-1">
        <h2 id="legal-publish-title" className="font-semibold">
          {revision.revisionNumber}번을 게시하기 전에 확인해 주세요
        </h2>
        <p className="text-sm text-muted-fg">
          게시하면 곧바로 스토어프론트 {LOCALE_LABELS[locale]} {TYPE_LABELS[type]} 페이지(
          {publicPathOf(type)})에 공개됩니다. 다른 언어 페이지에는 반영되지 않습니다. 이미 받은
          회원가입 · 주문 동의는 이 게시로 갱신된 것으로 보지 않습니다.
        </p>
      </div>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
        <dt className="text-muted-fg">공개 언어</dt>
        <dd>{LOCALE_LABELS[locale]}</dd>
        <dt className="text-muted-fg">리비전</dt>
        <dd>
          {revision.revisionNumber}번 · {formatLegalTime(revision.createdAt)} 저장
        </dd>
      </dl>
      <div className="max-h-96 overflow-y-auto">
        <TextPreview form={revision} />
      </div>
      <Checkbox
        label="최종 본문과 공개 언어를 확인했고, 실제 처리 사실과 최신 법령 · 전문가 검토 결과에 맞는지 검토했습니다."
        checked={checked}
        errors={errors}
        disabled={pending}
        onChange={(event) => onCheck(event.currentTarget.checked)}
      />
      <div className="flex flex-wrap gap-2">
        <Button onClick={onPublish} disabled={!checked || pending}>
          {pending ? '게시 중…' : `${revision.revisionNumber}번 게시 — 고객에게 공개`}
        </Button>
        <Button variant="secondary" onClick={onCancel} disabled={pending}>
          취소
        </Button>
      </div>
    </section>
  );
}

/** 이력 한 줄 — 공개 중 · 최신 표시와 본문 펼치기. 이전 본문은 편집기로 불러와 새 초안으로 저장해야 다시 게시할 수 있다 */
export function RevisionRow({
  revision,
  latest,
  published,
  onRestore,
}: {
  revision: LegalRevision;
  latest: boolean;
  published: boolean;
  onRestore: (revision: LegalRevision) => void;
}) {
  return (
    <li className="border-b border-border py-3 last:border-b-0">
      <details>
        <summary className="flex cursor-pointer flex-wrap items-baseline gap-x-3 gap-y-1 text-sm">
          <span className="font-medium">{revision.revisionNumber}번</span>
          <span className="min-w-0 break-words">{revision.title}</span>
          {published && (
            <span className="rounded-sm bg-primary px-1.5 text-xs text-primary-fg">공개 중</span>
          )}
          {latest && <span className="rounded-sm bg-muted px-1.5 text-xs">최신</span>}
          <span className="text-xs text-muted-fg">
            저장 {formatLegalTime(revision.createdAt)} ·{' '}
            {revision.publishedAt ? `게시 ${formatLegalTime(revision.publishedAt)}` : '게시 안 됨'}
          </span>
        </summary>
        <div className="mt-3 flex flex-col gap-3">
          <div className="max-h-80 overflow-y-auto">
            <TextPreview form={revision} />
          </div>
          {!latest && (
            <div>
              <Button variant="secondary" onClick={() => onRestore(revision)}>
                이 내용을 편집기로 불러오기
              </Button>
            </div>
          )}
        </div>
      </details>
    </li>
  );
}

/** 409 뒤 비교 — 내 입력과 지금 최신 리비전을 나란히 둔다 */
export function ConflictCompare({
  mine,
  latest,
  onTakeLatest,
}: {
  mine: LegalForm;
  latest: LegalRevision | null;
  onTakeLatest: () => void;
}) {
  return (
    <section
      aria-label="최신본과 비교"
      className="flex flex-col gap-3 rounded-md bg-muted px-4 py-3"
    >
      <div className="grid gap-4 lg:grid-cols-2">
        <div className="flex min-w-0 flex-col gap-2">
          <p className="text-sm font-medium">내 입력(저장 안 됨)</p>
          <div className="max-h-80 overflow-y-auto">
            <TextPreview form={mine} />
          </div>
        </div>
        <div className="flex min-w-0 flex-col gap-2">
          <p className="text-sm font-medium">
            {latest ? `지금 최신 ${latest.revisionNumber}번` : '지금 저장된 문서 없음'}
          </p>
          {latest && (
            <div className="max-h-80 overflow-y-auto">
              <TextPreview form={latest} />
            </div>
          )}
        </div>
      </div>
      <div>
        <Button variant="secondary" onClick={onTakeLatest}>
          내 입력 버리고 최신본으로 바꾸기
        </Button>
      </div>
    </section>
  );
}
