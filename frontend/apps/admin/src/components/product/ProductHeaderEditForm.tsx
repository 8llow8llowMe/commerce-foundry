'use client';

import { apiFetch, ApiError } from '@cf/api-client';
import { Button, Checkbox, FieldError, TextField } from '@cf/ui';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useId, useRef, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type FormErrors } from '@/lib/api-errors';
import {
  NAME_MAX,
  SALE_STATUS_LABELS,
  SUMMARY_MAX,
  type SaleStatusCode,
} from '@/lib/product/create';
import { errorSummary } from '@/lib/product/create-errors';
import { productDeletedHref } from '@/lib/product/delete';
import { adminProductPath, type AdminProductDetail } from '@/lib/product/detail';
import {
  headerFormOf,
  headerRequest,
  productHeaderPath,
  sameHeader,
  updatedProductHref,
  updateFailure,
  VERSION_MISSING,
  type ProductHeaderForm as Form,
  type ProductHeaderUpdateResponse,
} from '@/lib/product/update';
import { DescriptionHtmlField } from './DescriptionHtmlField';

/**
 * 어드민 상품 기본 정보 수정(#29 · BE #9 후속) — `PUT .../products/{productId}/header`.
 * 상세에서 읽은 `version` 을 되돌려 보낸다. 충돌(409) · 결과 모름은 폼을 지키고 "최신 내용 불러오기" 를 낸다 —
 * 누르면 상세를 다시 읽어 폼과 버전을 바꾼다(입력은 최신 값으로 바뀐다). 자동으로 다시 보내지 않는다.
 * 저장하면 상세로 옮기고, 상세 설명을 정리해 저장했으면 상세가 한 번 알린다(`?sanitized=1`).
 * 구역 · 저장 줄의 `data-tour` 는 온보딩 투어(#228 `product-edit-v1`)가 가리키는 앵커다 — 이름을 바꾸면 투어 정의도 같이 바꾼다.
 */
export function ProductHeaderEditForm({ product }: { product: AdminProductDetail }) {
  const id = useId();
  const router = useRouter();
  const [base, setBase] = useState(product);
  const [form, setForm] = useState<Form>(() => headerFormOf(product));
  const [errors, setErrors] = useState<FormErrors>({});
  const [reload, setReload] = useState(false);
  const [busy, setBusy] = useState<'saving' | 'reloading' | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const submitting = useRef(false);
  const summaryRef = useRef<HTMLDivElement>(null);

  const errorsOf = (key: string) => errors[key] ?? [];
  const set = (patch: Partial<Form>) => setForm((value) => ({ ...value, ...patch }));
  const unchanged = sameHeader(form, headerFormOf(base));
  const detailHref = `/products/${base.productId}`;

  const showErrors = (next: FormErrors, offerReload = false) => {
    setErrors(next);
    setReload(offerReload);
    requestAnimationFrame(() => summaryRef.current?.focus());
  };

  const submit = async () => {
    if (submitting.current) return;
    if (base.version === null) {
      showErrors({ form: [VERSION_MISSING] });
      return;
    }
    const built = headerRequest(form, base.version);
    if (!built.ok) {
      showErrors(built.errors);
      return;
    }
    submitting.current = true;
    setBusy('saving');
    setErrors({});
    setReload(false);
    try {
      const saved = await apiFetch<ProductHeaderUpdateResponse>(productHeaderPath(base.productId), {
        method: 'PUT',
        json: built.request,
      });
      // 상세로 옮길 때까지 버튼을 막아 둔다
      router.replace(updatedProductHref(saved));
    } catch (cause) {
      submitting.current = false;
      setBusy(null);
      const failure = updateFailure(cause);
      if (failure.kind === 'forbidden') setForbidden(true);
      else if (failure.kind === 'gone') router.replace(productDeletedHref('gone'));
      else showErrors(failure.errors, failure.reload);
    }
  };

  const reloadLatest = async () => {
    setBusy('reloading');
    try {
      const latest = await apiFetch<AdminProductDetail>(adminProductPath(base.productId));
      setBase(latest);
      setForm(headerFormOf(latest));
      setErrors({});
      setReload(false);
    } catch (cause) {
      const denied = accessDeniedOf(cause);
      if (denied === 'forbidden') setForbidden(true);
      else if (cause instanceof ApiError && cause.kind === 'notFound') {
        router.replace(productDeletedHref('gone'));
      } else showErrors({ form: [LOAD_FAILED] }, true);
    } finally {
      setBusy(null);
    }
  };

  if (forbidden) return <AccessNotice denied="forbidden" />;

  const summary = errorSummary(errors);

  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
      className="flex max-w-3xl flex-col gap-8"
      aria-labelledby={`${id}-title`}
    >
      <div className="flex flex-col gap-2">
        <Link
          href={detailHref}
          className="w-fit text-sm text-muted-fg underline-offset-4 hover:underline"
        >
          ← 상품 상세
        </Link>
        <h1 id={`${id}-title`} className="text-xl font-semibold">
          기본 정보 수정
        </h1>
        <p className="text-sm text-muted-fg">
          상품명 · 요약 · 상세 설명 · 판매 상태 · 노출을 바꿉니다. 카테고리 · 가격 · 옵션 · SKU ·
          이미지 · 맞춤 제작은 상품 상세의 구역별 수정에서 바꿉니다.
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
            {reload && (
              <button
                type="button"
                disabled={busy !== null}
                onClick={() => void reloadLatest()}
                className="mt-2 text-fg underline underline-offset-4 disabled:opacity-50"
              >
                {busy === 'reloading' ? '불러오는 중…' : '최신 내용 불러오기'}
              </button>
            )}
          </div>
        )}
      </div>

      <fieldset disabled={busy !== null} className="flex min-w-0 flex-col gap-6">
        <div className="flex flex-col gap-6" data-tour="product-edit-text">
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
          <DescriptionHtmlField
            value={form.descriptionHtml}
            onChange={(descriptionHtml) => set({ descriptionHtml })}
            errors={errorsOf('descriptionHtml')}
          />
        </div>
        <div className="flex flex-col gap-6" data-tour="product-edit-status">
          <div className="flex flex-col gap-1">
            <label htmlFor={`${id}-status`} className="text-sm font-medium">
              판매 상태
            </label>
            <select
              id={`${id}-status`}
              value={form.saleStatus}
              onChange={(event) => set({ saleStatus: event.currentTarget.value as SaleStatusCode })}
              className="h-10 w-40 rounded-md border border-border bg-bg px-3"
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
            label="스토어에 노출 — 끄면 고객에게 보이지 않습니다"
            checked={form.visible}
            onChange={(event) => set({ visible: event.currentTarget.checked })}
            errors={errorsOf('visible')}
          />
        </div>
      </fieldset>

      <div className="flex items-center gap-3" data-tour="product-edit-save">
        <Button type="submit" disabled={busy !== null || unchanged}>
          {busy === 'saving' ? '저장 중…' : '저장'}
        </Button>
        <Link
          href={detailHref}
          className="text-sm text-muted-fg underline-offset-4 hover:underline"
        >
          취소
        </Link>
        {unchanged && busy === null && (
          <span className="text-sm text-muted-fg">바뀐 내용이 없습니다.</span>
        )}
      </div>
    </form>
  );
}
