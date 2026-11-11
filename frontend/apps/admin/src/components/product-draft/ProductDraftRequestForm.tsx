'use client';

import { apiFetch } from '@cf/api-client';
import { Button, FieldError, TextField } from '@cf/ui';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, type AccessDenied, type FormErrors } from '@/lib/api-errors';
import { flattenTree, type CategoryRow } from '@/lib/category/tree';
import { CATEGORIES_PATH, type CategoryTree } from '@/lib/category/types';
import { isLongId } from '@/lib/product/query';
import { draftCreateFailure, UNKNOWN_OUTCOME } from '@/lib/product-draft/errors';
import {
  draftRequest,
  emptyDraftForm,
  LIST_LABELS,
  MEASUREMENTS_NOTE_MAX,
  NAME_HINT_MAX,
  ORIGIN_MAX,
  PRODUCT_DRAFTS_PATH,
  productDraftHref,
  TONE_MAX,
  type ListField,
  type ProductDraftForm,
} from '@/lib/product-draft/form';
import { DraftImagesField } from './DraftImagesField';
import { DraftListField } from './DraftListField';

export const FACTS_ONLY_NOTICE =
  '여기 적은 값만 사실로 쓰입니다. AI 는 사진만 보고 소재 · 세탁 방법 · 치수 · 원산지 · 인증을 정하지 않으니, 아는 사실은 직접 적어 주세요.';

const LIST_HINTS: Partial<Record<ListField, string>> = {
  materials: '예: 면 100%. 한 항목씩 더해 주세요.',
  mustAvoid: '상품 문구에 쓰지 않을 표현입니다.',
};

type Categories = { kind: 'loading' } | { kind: 'ok'; rows: CategoryRow[] } | { kind: 'failed' };

/**
 * 성공 응답 → 상세로 옮긴다. 응답의 아이디 모양이 다르면 옮기지 않고 false — 화면은 결과 모름으로 다룬다(초안은 생겼을 수 있다).
 */
export function afterDraftCreated(created: unknown, replace: (href: string) => void): boolean {
  const draftId = (created as { draftId?: unknown } | null)?.draftId;
  if (typeof draftId !== 'string' || !isLongId(draftId)) return false;
  replace(productDraftHref(draftId));
  return true;
}

/**
 * AI 상품 초안 요청(#360 · BE #359) — `POST /api/v1/admin/product-drafts`. 사진 · 사실을 받아 초안을 만들고 상세로 옮긴다.
 * 초안은 상품을 만들거나 공개하지 않는다. **멱등 키가 없다** — 제출은 ref 로 한 번만 받고, 성공하면 곧바로 상세로 옮긴다(replace).
 * 결과를 모르는 실패는 제출을 막아 두고 운영자가 "다시 요청할 수 있게 하기" 를 눌러야 연다(다시 보내면 초안이 하나 더 생긴다).
 * 사진은 올리는 동안 제출을 막는다(주소 없이 보내면 사진이 조용히 빠진다). 저장 401 은 폼을 지킨다.
 */
export function ProductDraftRequestForm() {
  const id = useId();
  const router = useRouter();
  const [form, setForm] = useState<ProductDraftForm>(emptyDraftForm);
  const [errors, setErrors] = useState<FormErrors>({});
  const [saving, setSaving] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [denied, setDenied] = useState<AccessDenied | null>(null);
  const [categories, setCategories] = useState<Categories>({ kind: 'loading' });
  const [uploading, setUploading] = useState<ReadonlySet<string>>(new Set());
  const submitting = useRef(false);
  const summaryRef = useRef<HTMLDivElement>(null);

  const errorsOf = useCallback((key: string) => errors[key] ?? [], [errors]);
  const set = (patch: Partial<ProductDraftForm>) => setForm((value) => ({ ...value, ...patch }));
  const setList = (key: ListField, items: string[]) =>
    setForm((value) => ({ ...value, [key]: items }));

  // 카테고리는 선택 칸이다 — 못 읽어도 요청할 수 있다(403 이면 요청도 403 이라 그때 권한 안내를 그린다)
  useEffect(() => {
    let alive = true;
    apiFetch<CategoryTree>(CATEGORIES_PATH).then(
      (data) => alive && setCategories({ kind: 'ok', rows: flattenTree(data.categories) }),
      (cause: unknown) => {
        if (!alive) return;
        const access = accessDeniedOf(cause);
        if (access === 'forbidden') setDenied(access);
        setCategories({ kind: 'failed' });
      },
    );
    return () => {
      alive = false;
    };
  }, []);

  const changeUploading = (slot: string, busy: boolean) =>
    setUploading((value) => {
      if (value.has(slot) === busy) return value;
      const next = new Set(value);
      if (busy) next.add(slot);
      else next.delete(slot);
      return next;
    });

  const showErrors = (next: FormErrors) => {
    setErrors(next);
    requestAnimationFrame(() => summaryRef.current?.focus());
  };

  const submit = async () => {
    if (submitting.current || uploading.size > 0) return;
    const built = draftRequest(form);
    if (!built.ok) {
      showErrors(built.errors);
      return;
    }
    submitting.current = true;
    setSaving(true);
    setErrors({});
    try {
      const created = await apiFetch<unknown>(PRODUCT_DRAFTS_PATH, {
        method: 'POST',
        json: built.request,
      });
      // 옮길 때까지 버튼을 막아 둔다(다시 누르면 초안이 하나 더 생긴다)
      if (!afterDraftCreated(created, router.replace)) {
        setSaving(false);
        setUncertain(true);
        showErrors({ form: [UNKNOWN_OUTCOME] });
      }
    } catch (cause) {
      const failure = draftCreateFailure(cause);
      setSaving(false);
      if (failure.kind === 'access') {
        submitting.current = false;
        setDenied(failure.denied);
        return;
      }
      if (!failure.uncertain) submitting.current = false;
      setUncertain(failure.uncertain);
      showErrors(failure.errors);
    }
  };

  if (denied) return <AccessNotice denied={denied} />;

  const formErrors = errors.form ?? [];
  const fieldCount = Object.keys(errors).filter(
    (key) => key !== 'form' && (errors[key]?.length ?? 0) > 0,
  ).length;

  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
      // 한 줄 칸의 Enter 로 제출하지 않는다 — 목록 칸은 Enter 가 "더하기" 이고, 제출은 버튼으로만 받는다
      onKeyDown={(event) => {
        if (event.key === 'Enter' && event.target instanceof HTMLInputElement) {
          event.preventDefault();
        }
      }}
      className="flex max-w-3xl flex-col gap-8"
      aria-labelledby={`${id}-title`}
    >
      <div className="flex flex-col gap-2">
        <Link
          href="/products/new"
          className="w-fit text-sm text-muted-fg underline-offset-4 hover:underline"
        >
          ← 상품 등록
        </Link>
        <h1 id={`${id}-title`} className="text-xl font-semibold">
          AI 상품 초안 요청
        </h1>
        <p className="text-sm text-muted-fg">
          사진과 아는 사실을 넣으면 상품 문구 초안을 만듭니다. 초안은 상품을 만들거나 공개하지
          않습니다 — 검토한 값만 나중에 상품 등록에 씁니다.
        </p>
        <p role="note" className="rounded-md bg-muted px-4 py-3 text-sm">
          {FACTS_ONLY_NOTICE}
        </p>
      </div>

      <div ref={summaryRef} tabIndex={-1} className="outline-none">
        {(formErrors.length > 0 || fieldCount > 0) && (
          <div role="alert" className="rounded-md bg-muted px-4 py-3 text-sm text-danger">
            <ul className="space-y-1">
              {formErrors.map((message) => (
                <li key={message}>{message}</li>
              ))}
              {fieldCount > 0 && (
                <li>입력값을 확인해 주세요. 칸 {fieldCount}곳에 안내가 있습니다.</li>
              )}
            </ul>
            {uncertain && (
              <button
                type="button"
                onClick={() => {
                  submitting.current = false;
                  setUncertain(false);
                }}
                className="mt-2 text-fg underline underline-offset-4"
              >
                알고 있습니다 — 다시 요청할 수 있게 하기
              </button>
            )}
          </div>
        )}
      </div>

      <fieldset disabled={saving} className="flex min-w-0 flex-col gap-8">
        <Section title="사진">
          <DraftImagesField
            value={form}
            onChange={({ thumbnail, gallery }) => set({ thumbnail, gallery })}
            errors={errorsOf('images')}
            disabled={saving}
            onUploadingChange={changeUploading}
            onAccessDenied={setDenied}
          />
        </Section>

        <Section title="기본 사실">
          <TextField
            label={`상품명 후보 (선택, ${NAME_HINT_MAX}자 이하)`}
            value={form.nameHint}
            onChange={(event) => set({ nameHint: event.currentTarget.value })}
            errors={errorsOf('nameHint')}
          />
          <div className="flex flex-col gap-1">
            <label htmlFor={`${id}-category`} className="text-sm font-medium">
              카테고리 (선택)
            </label>
            <select
              id={`${id}-category`}
              value={form.categoryId}
              disabled={categories.kind !== 'ok'}
              onChange={(event) => set({ categoryId: event.currentTarget.value })}
              className="h-10 max-w-md rounded-md border border-border bg-bg px-3"
            >
              <option value="">고르지 않음</option>
              {categories.kind === 'ok' &&
                categories.rows.map((row) => (
                  <option key={row.node.categoryId} value={row.node.categoryId}>
                    {row.path.join(' > ')}
                    {row.hiddenBy ? ' (숨김)' : ''}
                  </option>
                ))}
            </select>
            {categories.kind === 'loading' && (
              <p className="text-xs text-muted-fg">카테고리를 불러오는 중…</p>
            )}
            {categories.kind === 'failed' && (
              <p className="text-xs text-muted-fg">
                카테고리를 불러오지 못했습니다. 비워 두고 요청해도 됩니다.
              </p>
            )}
            <FieldError messages={errorsOf('categoryId')} />
          </div>
          <TextField
            label={`원산지 (선택, ${ORIGIN_MAX}자 이하)`}
            value={form.origin}
            onChange={(event) => set({ origin: event.currentTarget.value })}
            errors={errorsOf('origin')}
            className="max-w-md"
          />
          <MeasurementsNote
            id={`${id}-measurements`}
            value={form.measurementsNote}
            onChange={(measurementsNote) => set({ measurementsNote })}
            errors={errorsOf('measurementsNote')}
          />
        </Section>

        <Section title="소재 · 관리 · 인증">
          {(['materials', 'careNotes', 'certifications'] as const).map((key) => (
            <DraftListField
              key={key}
              label={LIST_LABELS[key]}
              hint={LIST_HINTS[key]}
              items={form[key]}
              onChange={(items) => setList(key, items)}
              errors={errorsOf(key)}
              disabled={saving}
            />
          ))}
        </Section>

        <Section title="문구 방향">
          <TextField
            label={`문구 분위기 (선택, ${TONE_MAX}자 이하)`}
            placeholder="예: 차분하고 단정하게"
            value={form.tone}
            onChange={(event) => set({ tone: event.currentTarget.value })}
            errors={errorsOf('tone')}
          />
          {(['mustInclude', 'mustAvoid'] as const).map((key) => (
            <DraftListField
              key={key}
              label={LIST_LABELS[key]}
              hint={LIST_HINTS[key]}
              items={form[key]}
              onChange={(items) => setList(key, items)}
              errors={errorsOf(key)}
              disabled={saving}
            />
          ))}
        </Section>
      </fieldset>

      <div className="flex items-center gap-3">
        <Button type="submit" disabled={saving || uncertain || uploading.size > 0}>
          {saving ? '요청 중…' : uploading.size > 0 ? '사진 올리는 중…' : '초안 요청'}
        </Button>
        <Link
          href="/products/new"
          className="text-sm text-muted-fg underline-offset-4 hover:underline"
        >
          취소
        </Link>
      </div>
    </form>
  );
}

function MeasurementsNote({
  id,
  value,
  onChange,
  errors,
}: {
  id: string;
  value: string;
  onChange: (value: string) => void;
  errors: readonly string[];
}) {
  const invalid = errors.length > 0;
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-sm font-medium">
        치수 메모 (선택, {MEASUREMENTS_NOTE_MAX}자 이하, 줄바꿈 가능)
      </label>
      <textarea
        id={id}
        value={value}
        rows={4}
        placeholder="예: 총장 62cm / 가슴단면 54cm"
        aria-invalid={invalid || undefined}
        aria-describedby={invalid ? `${id}-error` : undefined}
        onChange={(event) => onChange(event.currentTarget.value)}
        className="rounded-md border border-border bg-bg px-3 py-2"
      />
      <FieldError id={`${id}-error`} messages={errors} />
    </div>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-4">
      <h2 className="font-semibold">{title}</h2>
      {children}
    </section>
  );
}
