'use client';

import { apiFetch } from '@cf/api-client';
import { Button, Checkbox, cx, FieldError } from '@cf/ui';
import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import type { AccessDenied, FormErrors } from '@/lib/api-errors';
import { saveFailure, withSummary } from '@/lib/display/errors';
import {
  FIELDS_BY_TYPE,
  sectionRequest,
  sectionWarnings,
  TEXT_LABELS,
  type SectionForm,
  type TextFieldKey,
} from '@/lib/display/form';
import {
  DISPLAY_SECTIONS_PATH,
  ITEM_RANGES,
  LIMITS,
  type DisplaySectionAdmin,
} from '@/lib/display/types';
import { MediaFields } from './MediaFields';
import { ProductPickField } from './ProductPickField';

/** 칸 아래 안내 — 스토어프론트 메인이 그 칸을 어떻게 쓰는지(frontend/docs/architecture.md §10) */
const HINTS: Partial<Record<TextFieldKey, string>> = {
  title:
    '제목은 PC · 모바일 어느 화면 폭에서도 2줄을 넘지 않게 짧게 써 주세요. 길면 글자 크기를 줄이지 않고 단어를 줄입니다. 줄바꿈은 넣을 수 없습니다(화면 폭에 맞춰 자동으로 줄이 바뀝니다).',
  body: '줄바꿈은 메인에도 그대로 보입니다.',
  linkUrl:
    '/ 로 시작하는 쇼핑몰 안 주소(예: /shop) 또는 https:// 로 시작하는 주소만 넣을 수 있습니다. 비우면 버튼이 없습니다.',
  linkLabel: '비우면 메인의 기본 버튼 문구를 씁니다.',
};

/**
 * 메인 전시 섹션 만들기 · 고치기 폼(#71). 만들기는 `POST`, 고치기는 `PUT /{sectionId}`(전체 교체) — 본문은 같다.
 * 입력 제약은 BE 와 같게 미리 본다(`sectionRequest`) — 어기면 보내지 않고 칸에 오류를 붙인다. 정본은 서버다.
 * 부모가 `key` 로 대상을 넘겨, 대상이 바뀌면 폼을 새로 시작한다.
 */
export function SectionEditor({
  heading,
  sectionId,
  initial,
  onSaved,
  onReload,
  onDenied,
  onCancel,
  onFormChange,
}: {
  heading: string;
  /** 고칠 섹션. null 이면 새로 만든다 */
  sectionId: string | null;
  initial: SectionForm;
  /** 저장한 섹션 이름(안내 문구용) */
  onSaved: (name: string) => void;
  onReload: (notice: string) => void;
  onDenied: (denied: AccessDenied) => void;
  onCancel: () => void;
  /** 입력이 바뀔 때마다 지금 폼(저장 전) — 미리보기(#71)가 쓴다 */
  onFormChange?: (form: SectionForm) => void;
}) {
  const id = useId();
  const headingRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    headingRef.current?.focus();
  }, []);
  const [form, setForm] = useState<SectionForm>(initial);
  const [errors, setErrors] = useState<FormErrors>({});
  const [saving, setSaving] = useState(false);
  const errorsOf = (key: string) => errors[key] ?? [];
  const set = (patch: Partial<SectionForm>) => setForm((current) => ({ ...current, ...patch }));
  const formChanged = useRef(onFormChange);
  useEffect(() => {
    formChanged.current = onFormChange;
  });
  useEffect(() => {
    formChanged.current?.(form);
  }, [form]);
  const fields = FIELDS_BY_TYPE[form.sectionType];

  const submit = async () => {
    if (saving) return;
    const built = sectionRequest(form);
    if (!built.ok) {
      setErrors(withSummary(built.errors));
      return;
    }
    setSaving(true);
    setErrors({});
    try {
      const saved = await apiFetch<DisplaySectionAdmin>(
        sectionId === null
          ? DISPLAY_SECTIONS_PATH
          : `${DISPLAY_SECTIONS_PATH}/${encodeURIComponent(sectionId)}`,
        { method: sectionId === null ? 'POST' : 'PUT', json: built.request },
      );
      onSaved(saved.title ?? `${saved.sectionType.name} 섹션`);
    } catch (cause) {
      setSaving(false);
      const failure = saveFailure(cause, {
        type: form.sectionType,
        mediaCount: form.media.length,
      });
      if (failure.kind === 'access') onDenied(failure.denied);
      else if (failure.kind === 'reload') onReload(failure.notice);
      else setErrors(failure.errors);
    }
  };

  const warnings = sectionWarnings(form);

  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
      className="flex flex-col gap-5 rounded-md bg-muted/50 p-5"
      aria-labelledby={`${id}-heading`}
    >
      <h2
        id={`${id}-heading`}
        ref={headingRef}
        tabIndex={-1}
        className="font-semibold outline-none"
      >
        {heading}
      </h2>
      <fieldset disabled={saving} className="flex min-w-0 flex-col gap-5">
        {fields.map((key) => (
          <TextInput
            key={key}
            label={TEXT_LABELS[key]}
            value={form[key]}
            max={LIMITS[key]}
            multiline={key === 'body'}
            hint={HINTS[key]}
            errors={errorsOf(key)}
            onChange={(value) => set({ [key]: value })}
          />
        ))}

        <div className="flex flex-col gap-1">
          <Checkbox
            label="메인에 노출"
            checked={form.visible}
            onChange={(event) => set({ visible: event.currentTarget.checked })}
          />
          <p className="text-xs text-muted-fg">
            끄면 노출 기간과 관계없이 메인에 보이지 않습니다.
            {sectionId === null && ' 새 섹션은 꺼진 채로 시작합니다 — 내용을 확인한 뒤 켜세요.'}
          </p>
        </div>

        <PeriodFields
          startAt={form.startAt}
          endAt={form.endAt}
          errors={errorsOf('period')}
          onChange={(patch) => set(patch)}
        />

        {form.sectionType === 'PRODUCT_PICK' && (
          <ProductPickField
            max={ITEM_RANGES[form.sectionType].product[1]}
            surface="메인"
            products={form.products}
            errors={errorsOf('products')}
            onChange={(products) => {
              set({ products });
              // 오류 키가 줄 순서와 무관한 'products' 하나라 지워도 엉뚱한 줄에 붙지 않는다
              setErrors((current) => {
                const next = { ...current };
                delete next.products;
                return next;
              });
            }}
          />
        )}
        {form.media.length > 0 && (
          <MediaFields
            type={form.sectionType}
            media={form.media}
            errorsOf={errorsOf}
            onAltText={(index, altText) =>
              set({
                media: form.media.map((item, at) => (at === index ? { ...item, altText } : item)),
              })
            }
          />
        )}
        {form.media.length === 0 && form.sectionType === 'STORY' && (
          <p className="text-xs text-muted-fg">
            스토리 이미지 · 영상은 업로드 기능이 준비되면 넣을 수 있습니다. 지금은 문구만으로
            보입니다.
          </p>
        )}
      </fieldset>

      {warnings.length > 0 && (
        <ul className="flex flex-col gap-1 rounded-md bg-muted px-4 py-3 text-sm">
          {warnings.map((warning) => (
            <li key={warning}>{warning}</li>
          ))}
        </ul>
      )}
      <FieldError messages={errorsOf('form')} />
      <div className="flex gap-2">
        <Button type="submit" disabled={saving}>
          {saving ? '저장 중…' : '저장'}
        </Button>
        <Button variant="secondary" onClick={onCancel} disabled={saving}>
          취소
        </Button>
      </div>
    </form>
  );
}

/**
 * 글자 칸(한 줄 · 여러 줄). 길이는 BE 와 같은 기준(앞뒤 공백을 뺀 UTF-16 길이)으로 센다. 넘어도 자르지 않고 저장할 때 막는다.
 * 룩 폼(`LookEditor`)도 쓴다.
 */
export function TextInput({
  label,
  value,
  max,
  multiline,
  hint,
  errors,
  onChange,
}: {
  label: string;
  value: string;
  max: number;
  multiline: boolean;
  hint?: string;
  errors: string[];
  onChange: (value: string) => void;
}) {
  const id = useId();
  const invalid = errors.length > 0;
  const length = value.trim().length;
  const describedBy = [hint && `${id}-hint`, `${id}-count`, invalid && `${id}-error`]
    .filter(Boolean)
    .join(' ');
  const className = cx(
    'rounded-md border bg-bg px-3 text-fg outline-none focus:ring-2 focus:ring-primary',
    invalid ? 'border-danger' : 'border-border',
  );
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-baseline justify-between gap-4">
        <label htmlFor={id} className="text-sm font-medium">
          {label}
        </label>
        <span
          id={`${id}-count`}
          className={cx('text-xs', length > max ? 'text-danger' : 'text-muted-fg')}
        >
          {length} / {max}자
        </span>
      </div>
      {multiline ? (
        <textarea
          id={id}
          rows={5}
          value={value}
          aria-invalid={invalid || undefined}
          aria-describedby={describedBy}
          onChange={(event) => onChange(event.currentTarget.value)}
          className={cx(className, 'py-2')}
        />
      ) : (
        <input
          id={id}
          value={value}
          aria-invalid={invalid || undefined}
          aria-describedby={describedBy}
          onChange={(event) => onChange(event.currentTarget.value)}
          className={cx(className, 'h-10')}
        />
      )}
      {hint && (
        <p id={`${id}-hint`} className="text-xs text-muted-fg">
          {hint}
        </p>
      )}
      <FieldError id={`${id}-error`} messages={errors} />
    </div>
  );
}

/** 노출 기간 — 비우면 제한 없음. 시작은 포함, 종료는 제외다(그 시각부터 보이지 않는다) */
function PeriodFields({
  startAt,
  endAt,
  errors,
  onChange,
}: {
  startAt: string;
  endAt: string;
  errors: string[];
  onChange: (patch: { startAt?: string; endAt?: string }) => void;
}) {
  const id = useId();
  const invalid = errors.length > 0;
  const input = (key: 'startAt' | 'endAt', label: string, value: string): ReactNode => (
    <div className="flex flex-col gap-1">
      <label htmlFor={`${id}-${key}`} className="text-sm">
        {label}
      </label>
      <input
        id={`${id}-${key}`}
        type="datetime-local"
        value={value}
        aria-invalid={invalid || undefined}
        aria-describedby={`${id}-hint${invalid ? ` ${id}-error` : ''}`}
        onChange={(event) => onChange({ [key]: event.currentTarget.value })}
        className={cx(
          'h-10 rounded-md border bg-bg px-3 text-fg outline-none focus:ring-2 focus:ring-primary',
          invalid ? 'border-danger' : 'border-border',
        )}
      />
    </div>
  );
  return (
    <fieldset className="flex min-w-0 flex-col gap-2">
      <legend className="text-sm font-medium">노출 기간</legend>
      <div className="flex flex-wrap items-end gap-3">
        {input('startAt', '시작', startAt)}
        <span className="pb-2 text-sm text-muted-fg">~</span>
        {input('endAt', '종료', endAt)}
      </div>
      <p id={`${id}-hint`} className="text-xs text-muted-fg">
        비우면 제한이 없습니다. 이 컴퓨터의 시간대 기준이고, 종료 시각부터는 보이지 않습니다.
      </p>
      <FieldError id={`${id}-error`} messages={errors} />
    </fieldset>
  );
}
