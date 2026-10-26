'use client';

import { apiFetch } from '@cf/api-client';
import { Button, FieldError, TextField } from '@cf/ui';
import { useEffect, useId, useState, type FormEvent } from 'react';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied, type FormErrors } from '@/lib/api-errors';
import { templateSaveFailure, withSummary } from '@/lib/size-chart/errors';
import {
  emptyColumn,
  templateRequest,
  toTemplateForm,
  type ColumnRow,
  type TemplateForm,
} from '@/lib/size-chart/form';
import {
  LIMITS,
  TEMPLATES_PATH,
  templatePath,
  UNIT_LABELS,
  UNITS,
  type Template,
  type UnitCode,
} from '@/lib/size-chart/types';

const errorsOf = (errors: FormErrors, key: string) => errors[key] ?? [];

type Load =
  | { kind: 'loading' }
  | { kind: 'failed' }
  | { kind: 'ready'; form: TemplateForm; savedUnit: UnitCode | null; savedColumnIds: string[] };

/**
 * 템플릿 만들기 · 고치기 폼. 고칠 때는 상세를 다시 읽는다(목록 요약에는 항목이 없다).
 * 쓰는 중인 템플릿은 단위 · 항목 수를 바꾸면 409 `PRODUCT_062` 라 고치기 화면에서 미리 알린다 — 사용 여부는 서버만 안다.
 */
export function TemplateEditor({
  templateId,
  onSaved,
  onCancel,
  onReload,
  onDenied,
}: {
  /** null 이면 새 템플릿 */
  templateId: string | null;
  onSaved: (template: Template) => void;
  onCancel: () => void;
  /** 대상이 없어졌거나 결과를 모름 — 폼을 닫고 목록을 다시 읽는다 */
  onReload: (notice: string) => void;
  onDenied: (denied: AccessDenied) => void;
}) {
  const [load, setLoad] = useState<Load>(
    templateId
      ? { kind: 'loading' }
      : { kind: 'ready', form: toTemplateForm(null), savedUnit: null, savedColumnIds: [] },
  );
  const [errors, setErrors] = useState<FormErrors>({});
  const [saving, setSaving] = useState(false);
  const [retry, setRetry] = useState(0);
  const unitId = useId();

  useEffect(() => {
    if (!templateId) return;
    let alive = true;
    apiFetch<Template>(templatePath(templateId)).then(
      (template) => {
        if (!alive) return;
        const form = toTemplateForm(template);
        setLoad({
          kind: 'ready',
          form,
          savedUnit: form.baseUnit,
          savedColumnIds: template.columns.map((column) => column.columnId),
        });
      },
      (cause: unknown) => {
        if (!alive) return;
        const denied = accessDeniedOf(cause);
        if (denied) onDenied(denied);
        else setLoad({ kind: 'failed' });
      },
    );
    return () => {
      alive = false;
    };
  }, [templateId, retry, onDenied]);

  if (load.kind === 'loading') return <p className="text-sm text-muted-fg">불러오는 중…</p>;
  if (load.kind === 'failed') {
    return (
      <div role="alert" className="flex items-center gap-4">
        <p className="text-sm text-danger">{LOAD_FAILED}</p>
        <Button
          variant="secondary"
          onClick={() => {
            setLoad({ kind: 'loading' });
            setRetry((n) => n + 1);
          }}
        >
          다시 불러오기
        </Button>
        <Button variant="secondary" onClick={onCancel}>
          닫기
        </Button>
      </div>
    );
  }

  const { form } = load;
  const editing = templateId !== null;
  const update = (next: TemplateForm, rowsChanged = false) => {
    setLoad({ ...load, form: next });
    // 오류 키가 항목 순번이라 항목을 더하고 빼고 옮기면 엉뚱한 줄에 붙지 않게 지운다
    if (rowsChanged) setErrors({});
  };
  const setColumn = (index: number, patch: Partial<ColumnRow>) =>
    update({
      ...form,
      columns: form.columns.map((column, i) => (i === index ? { ...column, ...patch } : column)),
    });
  const move = (index: number, delta: number) => {
    const columns = [...form.columns];
    const [moved] = columns.splice(index, 1);
    if (!moved) return;
    columns.splice(index + delta, 0, moved);
    update({ ...form, columns }, true);
  };

  const structureChanged =
    editing &&
    (form.baseUnit !== load.savedUnit ||
      form.columns.some((column) => !column.columnId) ||
      load.savedColumnIds.some((id) => !form.columns.some((column) => column.columnId === id)));

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (saving) return;
    const built = templateRequest(form);
    if (!built.ok) {
      setErrors(withSummary(built.errors));
      return;
    }
    setSaving(true);
    setErrors({});
    try {
      const saved = await apiFetch<Template>(
        templateId ? templatePath(templateId) : TEMPLATES_PATH,
        {
          method: templateId ? 'PUT' : 'POST',
          json: built.request,
        },
      );
      onSaved(saved);
    } catch (cause) {
      const failure = templateSaveFailure(cause);
      if (failure.kind === 'access') onDenied(failure.denied);
      else if (failure.kind === 'reload') onReload(failure.notice);
      else setErrors(failure.errors);
    } finally {
      setSaving(false);
    }
  };

  return (
    <form
      onSubmit={submit}
      noValidate
      aria-label={editing ? '템플릿 고치기' : '새 템플릿'}
      className="flex flex-col gap-5 rounded-md border border-border p-5"
    >
      <h2 className="text-base font-semibold">{editing ? '템플릿 고치기' : '새 템플릿'}</h2>

      <TextField
        label="템플릿 이름"
        value={form.name}
        maxLength={LIMITS.templateName * 2}
        onChange={(event) => update({ ...form, name: event.currentTarget.value })}
        errors={errorsOf(errors, 'name')}
      />

      <div className="flex flex-col gap-1">
        <label htmlFor={unitId} className="text-sm font-medium">
          치수 단위
        </label>
        <select
          id={unitId}
          value={form.baseUnit}
          onChange={(event) => update({ ...form, baseUnit: event.currentTarget.value as UnitCode })}
          className="h-10 w-48 rounded-md border border-border bg-bg px-3"
        >
          {UNITS.map((unit) => (
            <option key={unit} value={unit}>
              {UNIT_LABELS[unit]}
            </option>
          ))}
        </select>
        <p className="text-xs text-muted-fg">
          치수를 이 단위로 입력 · 저장합니다. 영문 스토어의 단위 변환은 서버가 조회할 때 합니다.
        </p>
      </div>

      <fieldset className="flex flex-col gap-3">
        <legend className="mb-2 text-sm font-medium">
          측정 항목 ({form.columns.length}/{LIMITS.maxColumns})
        </legend>
        <p className="text-xs text-muted-fg">
          위에서부터 표의 왼쪽 → 오른쪽 순서입니다. 설명은 고객에게 재는 방법으로 보입니다.
        </p>
        <ol className="flex flex-col gap-3">
          {form.columns.map((column, index) => (
            <li
              key={column.key}
              className="flex flex-col gap-2 rounded-md bg-muted/50 p-3 md:flex-row md:items-start"
            >
              <TextField
                label={`항목 ${index + 1} 이름`}
                value={column.name}
                maxLength={LIMITS.columnName * 2}
                placeholder="예: 가슴 단면"
                onChange={(event) => setColumn(index, { name: event.currentTarget.value })}
                errors={errorsOf(errors, `columns.${index}.name`)}
                className="md:w-48"
              />
              <TextField
                label={`항목 ${index + 1} 설명 (선택)`}
                value={column.description}
                maxLength={LIMITS.columnDescription * 2}
                placeholder="예: 겨드랑이 아래 끝에서 끝까지"
                onChange={(event) => setColumn(index, { description: event.currentTarget.value })}
                errors={errorsOf(errors, `columns.${index}.description`)}
                className="flex-1"
              />
              <div className="flex gap-1 md:mt-6">
                <Button
                  type="button"
                  variant="secondary"
                  className="h-10 px-3"
                  aria-label={`항목 ${index + 1} 위로`}
                  disabled={index === 0}
                  onClick={() => move(index, -1)}
                >
                  ↑
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  className="h-10 px-3"
                  aria-label={`항목 ${index + 1} 아래로`}
                  disabled={index === form.columns.length - 1}
                  onClick={() => move(index, 1)}
                >
                  ↓
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  className="h-10 px-3"
                  aria-label={`항목 ${index + 1} 빼기`}
                  disabled={form.columns.length <= LIMITS.minColumns}
                  onClick={() =>
                    update({ ...form, columns: form.columns.filter((_, i) => i !== index) }, true)
                  }
                >
                  빼기
                </Button>
              </div>
            </li>
          ))}
        </ol>
        <FieldError messages={errorsOf(errors, 'columns')} />
        <div>
          <Button
            type="button"
            variant="secondary"
            disabled={form.columns.length >= LIMITS.maxColumns}
            onClick={() => update({ ...form, columns: [...form.columns, emptyColumn()] }, true)}
          >
            항목 더하기
          </Button>
        </div>
      </fieldset>

      {structureChanged && (
        <p className="text-sm text-muted-fg">
          단위를 바꾸거나 항목을 더하고 빼면, 이 템플릿을 쓰는 상품 사이즈표가 있을 때 저장되지
          않습니다. 그때는 새 템플릿을 만들어 주세요.
        </p>
      )}

      <FieldError messages={errorsOf(errors, 'form')} />
      <div className="flex gap-2">
        <Button type="submit" disabled={saving}>
          {saving ? '저장 중…' : '저장'}
        </Button>
        <Button type="button" variant="secondary" disabled={saving} onClick={onCancel}>
          취소
        </Button>
      </div>
    </form>
  );
}
