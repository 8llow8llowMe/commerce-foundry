'use client';

import { Button } from '@cf/ui';
import Link from 'next/link';
import { useId, type ReactNode } from 'react';
import { errorSummary } from '@/lib/product/create-errors';
import type { SectionSave } from '@/lib/product/use-section-save';

/** 상품 섹션 수정 화면의 머리(← 상품 상세 · 제목 · 설명) */
export function SectionEditHeader({
  productId,
  productName,
  title,
  titleId,
  children,
}: {
  productId: string;
  productName: string;
  title: string;
  titleId?: string;
  children?: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-2">
      <Link
        href={`/products/${productId}`}
        className="w-fit text-sm text-muted-fg underline-offset-4 hover:underline"
      >
        ← 상품 상세
      </Link>
      <h1 id={titleId} className="text-xl font-semibold">
        {title}
      </h1>
      <p className="text-sm text-muted-fg">{productName}</p>
      {children && <div className="text-sm text-muted-fg">{children}</div>}
    </div>
  );
}

/** 저장 실패 요약. 충돌 · 결과 모름이면 "최신 내용 불러오기" 를 함께 낸다 */
export function SectionErrors({ section }: { section: SectionSave }) {
  const summary = errorSummary(section.errors);
  return (
    <div id={section.summaryId} tabIndex={-1} className="outline-none">
      {summary.length > 0 && (
        <div role="alert" className="rounded-md bg-muted px-4 py-3 text-sm text-danger">
          <ul className="space-y-1">
            {summary.map((message) => (
              <li key={message}>{message}</li>
            ))}
          </ul>
          {section.reload && (
            <button
              type="button"
              disabled={section.busy !== null}
              onClick={() => void section.reloadLatest()}
              className="mt-2 text-fg underline underline-offset-4 disabled:opacity-50"
            >
              {section.busy === 'reloading' ? '불러오는 중…' : '최신 내용 불러오기'}
            </button>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * 섹션 하나를 저장하는 폼(#29). 입력 칸의 Enter 로 제출하지 않는다 — 목록을 고치다 덜 된 채 저장되지 않게 버튼으로만 저장한다.
 * `cancelHref` 가 있으면 저장 옆에 취소 링크를 둔다(옵션 · SKU 화면의 폼은 없다).
 */
export function SectionForm({
  section,
  title,
  description,
  unchanged,
  blocked = false,
  locked = false,
  saveLabel = '저장',
  cancelHref,
  onSave,
  children,
}: {
  section: SectionSave;
  onSave: () => void;
  /** 폼 제목(옵션 · SKU 화면처럼 한 화면에 폼이 여럿일 때) */
  title?: string;
  description?: ReactNode;
  unchanged: boolean;
  /** 저장만 따로 막는다(이미지 올리는 중 · 확인 전) — 칸은 그대로 고칠 수 있다 */
  blocked?: boolean;
  /** 칸과 저장을 모두 잠근다(한 화면의 다른 폼을 고치는 중) */
  locked?: boolean;
  saveLabel?: string;
  cancelHref?: string;
  children: ReactNode;
}) {
  const id = useId();
  const busy = section.busy !== null;
  return (
    <form
      noValidate
      aria-labelledby={title ? `${id}-title` : undefined}
      onSubmit={(event) => {
        event.preventDefault();
        onSave();
      }}
      onKeyDown={(event) => {
        if (event.key === 'Enter' && event.target instanceof HTMLInputElement) {
          event.preventDefault();
        }
      }}
      className="flex flex-col gap-6"
    >
      {title && (
        <div className="flex flex-col gap-1">
          <h2 id={`${id}-title`} className="font-semibold">
            {title}
          </h2>
          {description && <div className="text-sm text-muted-fg">{description}</div>}
        </div>
      )}
      <SectionErrors section={section} />
      <fieldset disabled={busy || locked} className="flex min-w-0 flex-col gap-6">
        {children}
      </fieldset>
      <SectionSaveRow
        section={section}
        unchanged={unchanged}
        blocked={blocked || locked}
        label={saveLabel}
        cancelHref={cancelHref}
      />
    </form>
  );
}

function SectionSaveRow({
  section,
  unchanged,
  blocked,
  label,
  cancelHref,
}: {
  section: SectionSave;
  unchanged: boolean;
  blocked: boolean;
  label: string;
  cancelHref?: string;
}) {
  return (
    <div className="flex flex-wrap items-center gap-3">
      <Button type="submit" disabled={section.busy !== null || unchanged || blocked}>
        {section.busy === 'saving' ? '저장 중…' : label}
      </Button>
      {cancelHref && (
        <Link
          href={cancelHref}
          className="text-sm text-muted-fg underline-offset-4 hover:underline"
        >
          취소
        </Link>
      )}
      {unchanged && section.busy === null && (
        <span className="text-sm text-muted-fg">바뀐 내용이 없습니다.</span>
      )}
    </div>
  );
}
