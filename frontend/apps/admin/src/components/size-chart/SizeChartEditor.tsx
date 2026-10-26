'use client';

import { ApiError, apiFetch, type PageResponse } from '@cf/api-client';
import { Button, cx, FieldError, TextField } from '@cf/ui';
import Link from 'next/link';
import {
  useCallback,
  useEffect,
  useId,
  useState,
  type ClipboardEvent,
  type FormEvent,
} from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied, type FormErrors } from '@/lib/api-errors';
import {
  chartSaveFailure,
  PRODUCT_GONE,
  SESSION_EXPIRED_ON_READ,
  TEMPLATE_CHANGED,
  TEMPLATE_CHANGED_DROPPED,
  withSummary,
} from '@/lib/size-chart/errors';
import {
  cellKey,
  chartRequest,
  emptyRow,
  labelKey,
  parsePastedTable,
  pasteIntoChart,
  toChartForm,
  withTemplate,
  droppedValues,
  type ChartForm,
} from '@/lib/size-chart/form';
import {
  LIMITS,
  productSizeChartPath,
  templatePath,
  TEMPLATES_PATH,
  type ProductSizeChart,
  type Template,
  type TemplateSummary,
} from '@/lib/size-chart/types';

/** 템플릿 고르기 목록은 한 번에 최대치(100)를 읽는다. 넘으면 최신 100개만 보인다고 알린다 */
const TEMPLATE_PAGE_SIZE = 100;

type Status =
  | { kind: 'loading' }
  | { kind: 'denied'; denied: AccessDenied }
  | { kind: 'notFound' }
  | { kind: 'loadFailed' }
  | { kind: 'ready' };

type Templates = { kind: 'ok'; list: PageResponse<TemplateSummary> } | { kind: 'failed' };

const errorsOf = (errors: FormErrors, key: string) => errors[key] ?? [];

/**
 * 상품 하나의 사이즈표 입력(#218). 템플릿을 고르고 사이즈(줄) × 항목(칸)에 치수를 넣는다.
 * - 저장은 전체 교체 PUT 이다(없으면 만든다). 버전이 없어 마지막 쓰기가 이긴다.
 * - 엑셀 · 구글 시트에서 복사한 표를 아무 칸에 붙여넣으면 그 칸부터 오른쪽 · 아래로 채운다(`pasteIntoChart`).
 * - 삭제 API 는 없다(BE #216 범위 밖) — 한 번 저장한 표는 고쳐 쓴다.
 */
export function SizeChartEditor({ productId }: { productId: string }) {
  const [status, setStatus] = useState<Status>({ kind: 'loading' });
  const [form, setForm] = useState<ChartForm>(() => toChartForm(null));
  const [saved, setSaved] = useState(false);
  const [templates, setTemplates] = useState<Templates | null>(null);
  const [productName, setProductName] = useState<string | null>(null);
  const [errors, setErrors] = useState<FormErrors>({});
  const [notice, setNotice] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [switching, setSwitching] = useState(false);
  const templateId = useId();

  // 상태는 응답 콜백에서만 바꾼다 — 마운트 effect 가 부른다
  const load = useCallback(
    (): Promise<boolean> =>
      apiFetch<ProductSizeChart | null>(productSizeChartPath(productId)).then(
        (chart) => {
          setForm(toChartForm(chart));
          setSaved(chart !== null);
          setStatus({ kind: 'ready' });
          return true;
        },
        (cause: unknown) => {
          const denied = accessDeniedOf(cause);
          if (denied) setStatus({ kind: 'denied', denied });
          else if (cause instanceof ApiError && cause.kind === 'notFound')
            setStatus({ kind: 'notFound' });
          else setStatus({ kind: 'loadFailed' });
          return false;
        },
      ),
    [productId],
  );

  const loadTemplates = useCallback(
    () =>
      apiFetch<PageResponse<TemplateSummary>>(
        `${TEMPLATES_PATH}?page=0&size=${TEMPLATE_PAGE_SIZE}`,
      ).then(
        (list) => setTemplates({ kind: 'ok', list }),
        (cause: unknown) => {
          const denied = accessDeniedOf(cause);
          if (denied) setStatus({ kind: 'denied', denied });
          else setTemplates({ kind: 'failed' });
        },
      ),
    [],
  );

  useEffect(() => {
    void load();
    void loadTemplates();
    // 상품 이름은 제목에만 쓴다 — 고객 상세라 숨긴 상품은 404 이고, 그때는 이름 없이 둔다
    let alive = true;
    apiFetch<{ name: string }>(`/api/v1/products/${encodeURIComponent(productId)}`).then(
      (product) => alive && setProductName(product.name),
      () => undefined,
    );
    return () => {
      alive = false;
    };
  }, [load, loadTemplates, productId]);

  const update = (next: ChartForm, rowsChanged = false) => {
    setForm(next);
    setNotice(null);
    // 오류 키가 줄 순번이라 줄을 더하고 빼고 옮기면 엉뚱한 줄에 붙지 않게 지운다
    if (rowsChanged) setErrors({});
  };

  /**
   * 템플릿을 읽어 칸을 바꾼다. `reread` 는 저장이 066~068(그사이 항목이 바뀜)이라 같은 템플릿을 다시 읽는 경우다.
   * 읽는 동안 표 · 저장은 막혀 있어(`fieldset disabled`) 지금 폼에 바로 적용한다.
   */
  const applyTemplate = async (id: string, reread = false) => {
    setSwitching(true);
    try {
      const template = await apiFetch<Template>(templatePath(id));
      const next = withTemplate(form, template);
      setForm(next);
      setErrors({});
      setNotice(
        reread ? (droppedValues(form, next) ? TEMPLATE_CHANGED_DROPPED : TEMPLATE_CHANGED) : null,
      );
    } catch (cause) {
      const denied = accessDeniedOf(cause);
      // 편집 중 읽기라 401 이어도 로그인 화면으로 보내지 않는다 — 입력을 지킨다
      if (denied === 'unauthorized') setErrors({ templateId: [SESSION_EXPIRED_ON_READ] });
      else if (denied) setStatus({ kind: 'denied', denied });
      else setErrors({ templateId: [LOAD_FAILED] });
    } finally {
      setSwitching(false);
    }
  };

  const setValue = (rowIndex: number, columnId: string, value: string) =>
    update({
      ...form,
      rows: form.rows.map((row, i) =>
        i === rowIndex ? { ...row, values: { ...row.values, [columnId]: value } } : row,
      ),
    });

  const moveRow = (index: number, delta: number) => {
    const rows = [...form.rows];
    const [moved] = rows.splice(index, 1);
    if (!moved) return;
    rows.splice(index + delta, 0, moved);
    update({ ...form, rows }, true);
  };

  /** 탭 · 줄바꿈이 든 글자만 표 붙여넣기로 다룬다. 한 칸 값은 입력칸 기본 동작에 맡긴다 */
  const paste = (rowIndex: number, startColumn: number) => (event: ClipboardEvent) => {
    const text = event.clipboardData.getData('text/plain');
    if (!/[\t\n]/.test(text.replace(/\r?\n$/, ''))) return;
    event.preventDefault();
    const result = pasteIntoChart(form, rowIndex, startColumn, parsePastedTable(text));
    update(result.form, true);
    setNotice(result.notice ?? '붙여넣었습니다. 값을 확인한 뒤 저장해 주세요.');
  };

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (saving) return;
    const built = chartRequest(form);
    if (!built.ok) {
      setErrors(withSummary(built.errors));
      setNotice(null);
      return;
    }
    setSaving(true);
    setErrors({});
    setNotice(null);
    try {
      const chart = await apiFetch<ProductSizeChart>(productSizeChartPath(productId), {
        method: 'PUT',
        json: built.request,
      });
      // 줄 key 는 그대로 둔다 — 새 key 면 입력칸이 다시 마운트되어 포커스를 잃는다
      const next = toChartForm(chart);
      setForm({
        ...next,
        rows: next.rows.map((row, i) => ({ ...row, key: form.rows[i]?.key ?? row.key })),
      });
      setSaved(true);
      setNotice('저장했습니다. 스토어 상품 상세의 사이즈 가이드에 바로 보입니다.');
    } catch (cause) {
      const failure = chartSaveFailure(cause, form.columns);
      if (failure.kind === 'access') setStatus({ kind: 'denied', denied: failure.denied });
      else if (failure.kind === 'productGone') setStatus({ kind: 'notFound' });
      else if (failure.kind === 'templateChanged' && form.templateId)
        await applyTemplate(form.templateId, true);
      else if (failure.kind === 'reload') {
        if (await load()) setNotice(failure.notice);
      } else if (failure.kind === 'errors') setErrors(failure.errors);
    } finally {
      setSaving(false);
    }
  };

  const heading = (
    <div className="flex flex-col gap-1">
      <Link href="/size-charts" className="text-sm text-muted-fg underline underline-offset-4">
        상품 목록
      </Link>
      <h1 className="text-xl font-semibold">{productName ?? '상품'} 사이즈표</h1>
    </div>
  );

  if (status.kind !== 'ready') {
    return (
      <div className="flex max-w-5xl flex-col gap-6">
        {heading}
        {status.kind === 'loading' && <p className="text-sm text-muted-fg">불러오는 중…</p>}
        {status.kind === 'denied' && <AccessNotice denied={status.denied} />}
        {status.kind === 'notFound' && <p className="text-sm text-muted-fg">{PRODUCT_GONE}</p>}
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
      </div>
    );
  }

  const options =
    templates?.kind === 'ok'
      ? templates.list.contents.map((t) => ({
          id: t.templateId,
          label: `${t.name} (${t.baseUnit.name})`,
        }))
      : [];
  // 저장된 표의 템플릿이 목록(최신 100개)에 없어도 고른 값으로 보이게
  if (form.templateId && !options.some((option) => option.id === form.templateId)) {
    options.unshift({ id: form.templateId, label: form.templateName });
  }
  const hasValues = form.rows.some((row) => Object.values(row.values).some((v) => v.trim() !== ''));
  const cellMessages = form.rows.flatMap((row, rowIndex) => {
    const name = row.sizeLabel.trim() || `${rowIndex + 1}번째 줄`;
    return [
      ...errorsOf(errors, labelKey(rowIndex)).map(
        (message) => `${rowIndex + 1}번째 줄 사이즈: ${message}`,
      ),
      ...form.columns.flatMap((column) =>
        errorsOf(errors, cellKey(rowIndex, column.columnId)).map(
          (message) => `${name} ${column.name}: ${message}`,
        ),
      ),
    ];
  });

  return (
    <form onSubmit={submit} noValidate className="flex max-w-5xl flex-col gap-6">
      {heading}
      <p className="text-sm text-muted-fg">
        {saved
          ? '저장된 사이즈표를 고칩니다.'
          : '아직 사이즈표가 없습니다. 템플릿을 고르고 치수를 넣어 주세요.'}{' '}
        사이즈표가 없는 상품은 스토어에 기존 안내(상세 설명 이미지 등)가 그대로 보입니다.
      </p>

      {/* 저장 · 템플릿 읽기 중에는 입력을 막는다 — 응답이 그사이 고친 값을 덮어쓰지 않게 */}
      <fieldset disabled={saving || switching} className="flex min-w-0 flex-col gap-6">
        <div className="flex flex-col gap-1">
          <label htmlFor={templateId} className="text-sm font-medium">
            템플릿
          </label>
          <div className="flex flex-wrap items-center gap-2">
            <select
              id={templateId}
              value={form.templateId ?? ''}
              disabled={switching || templates?.kind !== 'ok'}
              aria-invalid={errorsOf(errors, 'templateId').length > 0 || undefined}
              aria-describedby={
                errorsOf(errors, 'templateId').length > 0 ? `${templateId}-error` : undefined
              }
              onChange={(event) => {
                const id = event.currentTarget.value;
                if (id) void applyTemplate(id);
              }}
              className="h-10 min-w-0 rounded-md border border-border bg-bg px-3 md:w-80"
            >
              <option value="" disabled>
                {templates === null ? '불러오는 중…' : '템플릿을 고르세요'}
              </option>
              {options.map((option) => (
                <option key={option.id} value={option.id}>
                  {option.label}
                </option>
              ))}
            </select>
            <Link
              href="/size-chart-templates"
              className="text-sm text-muted-fg underline underline-offset-4"
            >
              템플릿 관리
            </Link>
          </div>
          {templates?.kind === 'failed' && (
            <div className="flex items-center gap-3">
              <p className="text-sm text-danger">템플릿 목록을 불러오지 못했습니다.</p>
              <Button variant="secondary" className="h-8 px-3" onClick={() => void loadTemplates()}>
                다시 불러오기
              </Button>
            </div>
          )}
          {templates?.kind === 'ok' && templates.list.contents.length === 0 && (
            <p className="text-sm text-muted-fg">
              템플릿이 없습니다. 템플릿 관리에서 먼저 만들어 주세요.
            </p>
          )}
          {templates?.kind === 'ok' && templates.list.totalElements > TEMPLATE_PAGE_SIZE && (
            <p className="text-xs text-muted-fg">최신 {TEMPLATE_PAGE_SIZE}개 템플릿만 보입니다.</p>
          )}
          {hasValues && (
            <p className="text-xs text-muted-fg">
              다른 템플릿을 고르면 입력한 치수는 지워지고 사이즈 이름만 남습니다.
            </p>
          )}
          <FieldError id={`${templateId}-error`} messages={errorsOf(errors, 'templateId')} />
        </div>

        {form.templateId && form.columns.length > 0 && (
          <section aria-labelledby={`${templateId}-grid`} className="flex flex-col gap-3">
            <div>
              <h2 id={`${templateId}-grid`} className="text-base font-semibold">
                치수 ({form.unitName})
              </h2>
              <p className="mt-1 text-xs text-muted-fg">
                엑셀 · 구글 시트에서 표를 복사해 아무 칸에 붙여넣으면 그 칸부터 채웁니다. 첫 줄이
                항목 이름이면 건너뜁니다. 숫자만 넣어 주세요(소수 둘째 자리까지).
              </p>
            </div>
            {/* relative — 표 머리의 sr-only(absolute)가 스크롤 상자 밖으로 나가 화면을 가로로 넓히지 않게 */}
            <div className="relative overflow-x-auto">
              <table className="text-left text-sm">
                <thead className="text-muted-fg">
                  <tr>
                    <th scope="col" className="py-2 pr-2 font-medium">
                      사이즈
                    </th>
                    {form.columns.map((column) => (
                      <th
                        key={column.columnId}
                        scope="col"
                        title={column.description ?? undefined}
                        className="py-2 pr-2 font-medium whitespace-nowrap"
                      >
                        {column.name}
                      </th>
                    ))}
                    <th scope="col" className="py-2 font-medium">
                      <span className="sr-only">줄 동작</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {form.rows.map((row, rowIndex) => {
                    const rowName = row.sizeLabel.trim() || `${rowIndex + 1}번째 줄`;
                    const labelInvalid = errorsOf(errors, labelKey(rowIndex)).length > 0;
                    return (
                      <tr key={row.key}>
                        <td className="py-1 pr-2">
                          <input
                            aria-label={`${rowIndex + 1}번째 줄 사이즈 이름`}
                            aria-invalid={labelInvalid || undefined}
                            aria-describedby={
                              labelInvalid ? `${templateId}-cells-error` : undefined
                            }
                            value={row.sizeLabel}
                            maxLength={LIMITS.sizeLabel * 2}
                            placeholder="예: M"
                            onPaste={paste(rowIndex, -1)}
                            onChange={(event) => {
                              const sizeLabel = event.currentTarget.value;
                              update({
                                ...form,
                                rows: form.rows.map((r, i) =>
                                  i === rowIndex ? { ...r, sizeLabel } : r,
                                ),
                              });
                            }}
                            className={cx(
                              'h-9 w-24 rounded-md border bg-bg px-2',
                              labelInvalid ? 'border-danger' : 'border-border',
                            )}
                          />
                        </td>
                        {form.columns.map((column, columnIndex) => {
                          const invalid =
                            errorsOf(errors, cellKey(rowIndex, column.columnId)).length > 0;
                          return (
                            <td key={column.columnId} className="py-1 pr-2">
                              <input
                                aria-label={`${rowName} ${column.name}`}
                                aria-invalid={invalid || undefined}
                                aria-describedby={invalid ? `${templateId}-cells-error` : undefined}
                                inputMode="decimal"
                                autoComplete="off"
                                value={row.values[column.columnId] ?? ''}
                                onPaste={paste(rowIndex, columnIndex)}
                                onChange={(event) =>
                                  setValue(rowIndex, column.columnId, event.currentTarget.value)
                                }
                                className={cx(
                                  'h-9 w-20 rounded-md border bg-bg px-2 text-right tabular-nums',
                                  invalid ? 'border-danger' : 'border-border',
                                )}
                              />
                            </td>
                          );
                        })}
                        <td className="py-1 whitespace-nowrap">
                          <div className="flex gap-1">
                            <Button
                              variant="secondary"
                              className="h-9 px-2"
                              aria-label={`${rowName} 위로`}
                              disabled={rowIndex === 0}
                              onClick={() => moveRow(rowIndex, -1)}
                            >
                              ↑
                            </Button>
                            <Button
                              variant="secondary"
                              className="h-9 px-2"
                              aria-label={`${rowName} 아래로`}
                              disabled={rowIndex === form.rows.length - 1}
                              onClick={() => moveRow(rowIndex, 1)}
                            >
                              ↓
                            </Button>
                            <Button
                              variant="secondary"
                              className="h-9 px-2"
                              aria-label={`${rowName} 빼기`}
                              disabled={form.rows.length <= LIMITS.minRows}
                              onClick={() =>
                                update(
                                  { ...form, rows: form.rows.filter((_, i) => i !== rowIndex) },
                                  true,
                                )
                              }
                            >
                              빼기
                            </Button>
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <FieldError
              id={`${templateId}-cells-error`}
              messages={[...errorsOf(errors, 'rows'), ...cellMessages]}
            />
            <div>
              <Button
                variant="secondary"
                disabled={form.rows.length >= LIMITS.maxRows}
                onClick={() => update({ ...form, rows: [...form.rows, emptyRow()] }, true)}
              >
                사이즈 더하기 ({form.rows.length}/{LIMITS.maxRows})
              </Button>
            </div>
            {form.columns.some((column) => column.description) && (
              <dl className="grid gap-x-4 gap-y-1 text-xs text-muted-fg md:grid-cols-[auto_1fr]">
                {form.columns
                  .filter((column) => column.description)
                  .map((column) => (
                    <div key={column.columnId} className="contents">
                      <dt className="font-medium">{column.name}</dt>
                      <dd>{column.description}</dd>
                    </div>
                  ))}
              </dl>
            )}
          </section>
        )}

        <TextField
          label="표 아래 안내 (선택)"
          value={form.note}
          maxLength={LIMITS.note * 2}
          placeholder="예: 재는 방법에 따라 1~2cm 차이가 있을 수 있습니다."
          onChange={(event) => update({ ...form, note: event.currentTarget.value })}
          errors={errorsOf(errors, 'note')}
        />
      </fieldset>

      {/* live region 은 늘 두고 내용만 바꾼다 — 처음 나타날 때도 읽히게 */}
      <p role="status" className="text-sm empty:hidden">
        {notice ?? ''}
      </p>
      <FieldError messages={errorsOf(errors, 'form')} />
      <div>
        <Button type="submit" disabled={saving || switching || !form.templateId}>
          {saving ? '저장 중…' : '저장'}
        </Button>
      </div>
    </form>
  );
}
