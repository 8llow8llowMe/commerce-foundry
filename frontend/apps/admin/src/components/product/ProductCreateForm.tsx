'use client';

import { apiFetch, type Currency } from '@cf/api-client';
import { Button, Checkbox, FieldError, TextField } from '@cf/ui';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied, type FormErrors } from '@/lib/api-errors';
import { flattenTree, type CategoryRow } from '@/lib/category/tree';
import { CATEGORIES_PATH, type CategoryTree } from '@/lib/category/types';
import {
  CATEGORY_IDS_MAX,
  CREATE_PRODUCT_PATH,
  createdProductHref,
  CURRENCIES,
  DESCRIPTION_MAX,
  emptyProductForm,
  localToday,
  MAX_QUANTITY_MAX,
  NAME_MAX,
  productRequest,
  pruneCategories,
  SALE_STATUS_LABELS,
  skuRows,
  SUMMARY_MAX,
  type OptionGroupForm,
  type ProductCreateForm as Form,
  type ProductCreateResponse,
  type SaleStatusCode,
} from '@/lib/product/create';
import { createFailure, errorSummary } from '@/lib/product/create-errors';
import { OptionEditor } from './OptionEditor';
import { SkuTable } from './SkuTable';

const selectClass = 'h-10 rounded-md border border-border bg-bg px-3';

type Categories =
  | { kind: 'loading' }
  | { kind: 'ok'; rows: CategoryRow[] }
  | { kind: 'failed' }
  | { kind: 'denied'; denied: AccessDenied };

/**
 * 어드민 상품 등록(#29 · BE #9 E1) — `POST /api/v1/admin/products`. 이미지 · 맞춤 제작 · 수정은 아직 없다(BE 미머지).
 * **멱등 키가 없다** — 같은 본문을 두 번 보내면 상품이 둘 생긴다. 그래서 제출은 ref 로 한 번만 받고, 성공하면 곧바로 상세로 옮긴다(replace).
 * 결과를 모르는 실패는 다시 보내지 않고 목록에서 확인하게 한다. 저장 401 은 폼을 지킨다(§9 공통 규칙).
 */
export function ProductCreateForm() {
  const id = useId();
  const router = useRouter();
  const [form, setForm] = useState<Form>(emptyProductForm);
  const [errors, setErrors] = useState<FormErrors>({});
  const [uncertain, setUncertain] = useState(false);
  const [saving, setSaving] = useState(false);
  const [denied, setDenied] = useState<AccessDenied | null>(null);
  const [categories, setCategories] = useState<Categories>({ kind: 'loading' });
  const [categoryLoad, setCategoryLoad] = useState(0);
  const submitting = useRef(false);
  const summaryRef = useRef<HTMLDivElement>(null);

  const errorsOf = useCallback((key: string) => errors[key] ?? [], [errors]);
  const set = (patch: Partial<Form>) => setForm((value) => ({ ...value, ...patch }));

  // 카테고리 트리(숨김 포함). 다시 읽으면 트리에 없는 고른 값을 뺀다(PRODUCT_012 뒤)
  useEffect(() => {
    let alive = true;
    apiFetch<CategoryTree>(CATEGORIES_PATH).then(
      (data) => {
        if (!alive) return;
        const rows = flattenTree(data.categories);
        setCategories({ kind: 'ok', rows });
        setForm((value) => pruneCategories(value, rows));
      },
      (cause: unknown) => {
        if (!alive) return;
        const access = accessDeniedOf(cause);
        setCategories(access ? { kind: 'denied', denied: access } : { kind: 'failed' });
      },
    );
    return () => {
      alive = false;
    };
  }, [categoryLoad]);

  const reloadCategories = () => {
    setCategories({ kind: 'loading' });
    setCategoryLoad((n) => n + 1);
  };

  /** 옵션을 바꾸면 SKU 줄 번호가 바뀐다 — 옛 번호에 붙은 서버 오류를 지운다 */
  const changeGroups = (optionGroups: OptionGroupForm[]) => {
    set({ optionGroups });
    setErrors((value) =>
      Object.fromEntries(
        Object.entries(value).filter(
          ([key]) => !key.startsWith('skus') && !key.startsWith('optionGroups'),
        ),
      ),
    );
  };

  const showErrors = (next: FormErrors) => {
    setErrors(next);
    // 요약으로 포커스를 옮겨 화면 읽기 프로그램에도 알린다
    requestAnimationFrame(() => summaryRef.current?.focus());
  };

  const submit = async () => {
    if (submitting.current) return;
    const built = productRequest(form);
    if (!built.ok) {
      setUncertain(false);
      showErrors(built.errors);
      return;
    }
    submitting.current = true;
    setSaving(true);
    setErrors({});
    setUncertain(false);
    try {
      const created = await apiFetch<ProductCreateResponse>(CREATE_PRODUCT_PATH, {
        method: 'POST',
        json: built.request,
      });
      // 상세로 옮길 때까지 버튼을 막아 둔다(다시 누르면 상품이 하나 더 생긴다)
      router.replace(createdProductHref(created));
    } catch (cause) {
      const failure = createFailure(cause);
      setSaving(false);
      if (failure.kind === 'access') {
        submitting.current = false;
        setDenied(failure.denied);
        return;
      }
      // 등록됐는지 모르는 실패는 제출을 계속 막는다 — 운영자가 목록에서 확인하고 "없었다" 를 누른 뒤에만 다시 연다
      // (멱등 키가 없어 다시 누르면 상품이 하나 더 생기고, 아직 삭제 API 가 없다)
      if (!failure.uncertain) submitting.current = false;
      setUncertain(failure.uncertain);
      showErrors(failure.errors);
      if (failure.reloadCategories) reloadCategories();
    }
  };

  if (denied) return <AccessNotice denied={denied} />;
  if (categories.kind === 'denied') return <AccessNotice denied={categories.denied} />;

  const rows = skuRows(form);
  const summary = errorSummary(errors);
  const currencyUnit = form.currency === 'KRW' ? '원' : '달러, 센트까지';
  const categoryRows = categories.kind === 'ok' ? categories.rows : [];
  const pathOf = (row: CategoryRow) => `${row.path.join(' > ')}${row.hiddenBy ? ' (숨김)' : ''}`;

  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
      // 입력 칸의 Enter 로 제출하지 않는다 — 옵션 · 재고를 채우다 덜 된 상품이 생기고, 아직 수정 · 삭제 API 가 없다. 제출은 버튼으로만
      onKeyDown={(event) => {
        if (event.key === 'Enter' && event.target instanceof HTMLInputElement) {
          event.preventDefault();
        }
      }}
      className="flex max-w-5xl flex-col gap-8"
      aria-labelledby={`${id}-title`}
    >
      <div className="flex flex-col gap-2">
        <Link
          href="/products"
          className="w-fit text-sm text-muted-fg underline-offset-4 hover:underline"
        >
          ← 상품 목록
        </Link>
        <h1 id={`${id}-title`} className="text-xl font-semibold">
          상품 등록
        </h1>
        <p className="text-sm text-muted-fg">
          이미지는 이미지 추가 기능이 준비되면 등록한 상품에 붙일 수 있습니다. 같은 내용으로 두 번
          등록하면 상품이 두 개 생깁니다.
        </p>
      </div>

      <div ref={summaryRef} tabIndex={-1} className="outline-none">
        {summary.length > 0 && (
          <div role="alert" className="rounded-md bg-muted px-4 py-3 text-sm text-danger">
            <ul className="space-y-1">
              {summary.map((message) => (
                <li key={message}>{message}</li>
              ))}
            </ul>
            {uncertain && (
              <div className="mt-2 flex flex-wrap items-center gap-4">
                <Link
                  href="/products"
                  target="_blank"
                  className="inline-block text-fg underline underline-offset-4"
                >
                  상품 목록에서 확인하기(새 탭)
                </Link>
                <button
                  type="button"
                  onClick={() => {
                    submitting.current = false;
                    setUncertain(false);
                  }}
                  className="text-fg underline underline-offset-4"
                >
                  목록에 없었습니다 — 다시 등록할 수 있게 하기
                </button>
              </div>
            )}
          </div>
        )}
      </div>

      <fieldset disabled={saving} className="flex min-w-0 flex-col gap-8">
        <Section title="기본 정보">
          <TextField
            label={`상품명 (${NAME_MAX}자 이하)`}
            value={form.name}
            onChange={(event) => set({ name: event.currentTarget.value })}
            errors={errorsOf('name')}
            required
          />
          <TextField
            label={`요약 (선택, ${SUMMARY_MAX}자 이하)`}
            value={form.summary}
            onChange={(event) => set({ summary: event.currentTarget.value })}
            errors={errorsOf('summary')}
          />
          <div className="flex flex-col gap-1">
            <label htmlFor={`${id}-description`} className="text-sm font-medium">
              상세 설명 HTML (선택)
            </label>
            <textarea
              id={`${id}-description`}
              value={form.descriptionHtml}
              rows={10}
              aria-invalid={errorsOf('descriptionHtml').length > 0 || undefined}
              aria-describedby={`${id}-description-help${errorsOf('descriptionHtml').length > 0 ? ` ${id}-description-error` : ''}`}
              onChange={(event) => set({ descriptionHtml: event.currentTarget.value })}
              className="rounded-md border border-border bg-bg px-3 py-2 font-mono text-sm text-fg outline-none focus:ring-2 focus:ring-primary"
            />
            <p id={`${id}-description-help`} className="text-xs text-muted-fg">
              {form.descriptionHtml.length.toLocaleString('ko-KR')} /{' '}
              {DESCRIPTION_MAX.toLocaleString('ko-KR')}자. 허용되지 않은 태그 · 속성 · 상대 주소는
              저장할 때 빠집니다.
            </p>
            <FieldError id={`${id}-description-error`} messages={errorsOf('descriptionHtml')} />
          </div>
        </Section>

        <Section title="카테고리">
          {categories.kind === 'loading' && (
            <p className="text-sm text-muted-fg">카테고리를 불러오는 중…</p>
          )}
          {categories.kind === 'failed' && (
            <div role="alert" className="flex items-center gap-4">
              <p className="text-sm text-danger">{LOAD_FAILED}</p>
              <Button variant="secondary" onClick={reloadCategories}>
                다시 불러오기
              </Button>
            </div>
          )}
          {categories.kind === 'ok' && categoryRows.length === 0 && (
            <p className="text-sm text-muted-fg">
              카테고리가 없습니다.{' '}
              <Link href="/categories" className="text-fg underline underline-offset-4">
                카테고리 화면
              </Link>
              에서 먼저 만들어 주세요.
            </p>
          )}
          {categories.kind === 'ok' && categoryRows.length > 0 && (
            <>
              <div className="flex flex-col gap-1">
                <label htmlFor={`${id}-category`} className="text-sm font-medium">
                  대표 카테고리
                </label>
                <select
                  id={`${id}-category`}
                  value={form.categoryId}
                  aria-invalid={errorsOf('categoryId').length > 0 || undefined}
                  aria-describedby={
                    errorsOf('categoryId').length > 0 ? `${id}-category-error` : undefined
                  }
                  onChange={(event) => set({ categoryId: event.currentTarget.value })}
                  className={`${selectClass} max-w-md`}
                >
                  <option value="">고르세요</option>
                  {categoryRows.map((row) => (
                    <option key={row.node.categoryId} value={row.node.categoryId}>
                      {pathOf(row)}
                    </option>
                  ))}
                </select>
                <FieldError id={`${id}-category-error`} messages={errorsOf('categoryId')} />
              </div>
              <fieldset className="flex flex-col gap-1">
                <legend className="text-sm font-medium">
                  함께 진열할 카테고리 (선택, 대표 포함 {CATEGORY_IDS_MAX}개까지)
                </legend>
                <div className="max-h-60 overflow-y-auto rounded-md border border-border p-3">
                  {categoryRows.map((row) => {
                    const primary = row.node.categoryId === form.categoryId;
                    return (
                      <Checkbox
                        key={row.node.categoryId}
                        label={primary ? `${pathOf(row)} — 대표` : pathOf(row)}
                        checked={primary || form.categoryIds.includes(row.node.categoryId)}
                        disabled={primary}
                        onChange={(event) => {
                          const checked = event.currentTarget.checked;
                          setForm((value) => ({
                            ...value,
                            categoryIds: checked
                              ? [...value.categoryIds, row.node.categoryId]
                              : value.categoryIds.filter((item) => item !== row.node.categoryId),
                          }));
                        }}
                        className="py-1"
                      />
                    );
                  })}
                </div>
                <FieldError messages={errorsOf('categoryIds')} />
              </fieldset>
            </>
          )}
        </Section>

        <Section title="판매 · 노출">
          <div className="flex flex-col gap-1">
            <label htmlFor={`${id}-status`} className="text-sm font-medium">
              판매 상태
            </label>
            <select
              id={`${id}-status`}
              value={form.saleStatus}
              onChange={(event) => set({ saleStatus: event.currentTarget.value as SaleStatusCode })}
              className={`${selectClass} w-40`}
            >
              {(Object.keys(SALE_STATUS_LABELS) as SaleStatusCode[]).map((code) => (
                <option key={code} value={code}>
                  {SALE_STATUS_LABELS[code]}
                </option>
              ))}
            </select>
            <FieldError messages={errorsOf('saleStatus')} />
          </div>
          <Checkbox
            label="스토어에 노출 — 끄면 숨긴 상품으로 등록되고 고객에게 보이지 않습니다"
            checked={form.visible}
            onChange={(event) => set({ visible: event.currentTarget.checked })}
            errors={errorsOf('visible')}
          />
        </Section>

        <Section title="가격">
          <div className="flex flex-col gap-1">
            <label htmlFor={`${id}-currency`} className="text-sm font-medium">
              통화
            </label>
            <select
              id={`${id}-currency`}
              value={form.currency}
              onChange={(event) => set({ currency: event.currentTarget.value as Currency })}
              className={`${selectClass} w-32`}
            >
              {CURRENCIES.map((code) => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </select>
            <p className="text-xs text-muted-fg">
              판매가 · 정가 · 추가금이 모두 이 통화입니다. 환율로 바꾸지 않습니다.
            </p>
          </div>
          <div className="flex flex-wrap items-start gap-3">
            <TextField
              label={`판매가 (${currencyUnit})`}
              inputMode="decimal"
              value={form.salePrice}
              onChange={(event) => set({ salePrice: event.currentTarget.value })}
              errors={errorsOf('salePrice')}
              className="w-48"
              required
            />
            <TextField
              label={`정가 (선택, ${currencyUnit})`}
              inputMode="decimal"
              value={form.listPrice}
              onChange={(event) => set({ listPrice: event.currentTarget.value })}
              errors={errorsOf('listPrice')}
              className="w-48"
            />
            <TextField
              label={`1회 최대 주문 수량 (선택, 1~${MAX_QUANTITY_MAX})`}
              inputMode="numeric"
              value={form.maxQuantityPerOrder}
              onChange={(event) => set({ maxQuantityPerOrder: event.currentTarget.value })}
              errors={errorsOf('maxQuantityPerOrder')}
              className="w-56"
            />
          </div>
          <p className="text-xs text-muted-fg">
            정가는 할인 전 가격이고 판매가보다 높아야 합니다. 할인이 없으면 비워 두세요. 1회 최대
            수량을 비우면 주문 기본값이 걸립니다.
          </p>
        </Section>

        <Section title="프리오더">
          <Checkbox
            label="프리오더(예약 판매) 상품"
            checked={form.preorder}
            onChange={(event) => set({ preorder: event.currentTarget.checked })}
          />
          {form.preorder && (
            <TextField
              label="발송 예정일 (선택 — 비우면 추후 안내)"
              type="date"
              min={localToday()}
              value={form.expectedShipDate}
              onChange={(event) => set({ expectedShipDate: event.currentTarget.value })}
              errors={errorsOf('expectedShipDate')}
              className="w-64"
            />
          )}
          {!form.preorder && <FieldError messages={errorsOf('expectedShipDate')} />}
        </Section>

        <Section title="옵션">
          <OptionEditor
            groups={form.optionGroups}
            errorsOf={errorsOf}
            disabled={saving}
            onChange={changeGroups}
          />
        </Section>

        <Section title={`옵션 조합(SKU) ${rows.length}개`}>
          <SkuTable
            rows={rows}
            groups={form.optionGroups}
            currencyUnit={currencyUnit}
            errorsOf={errorsOf}
            onChange={(key, sku) =>
              setForm((value) => ({ ...value, skus: { ...value.skus, [key]: sku } }))
            }
          />
        </Section>
      </fieldset>

      <div className="flex items-center gap-3">
        <Button type="submit" disabled={saving || uncertain || categories.kind !== 'ok'}>
          {saving ? '등록 중…' : '상품 등록'}
        </Button>
        <Link href="/products" className="text-sm text-muted-fg underline-offset-4 hover:underline">
          취소
        </Link>
      </div>
    </form>
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
