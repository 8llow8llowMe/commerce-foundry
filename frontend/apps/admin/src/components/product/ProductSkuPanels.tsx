'use client';

import { apiFetch } from '@cf/api-client';
import { Button, Checkbox, FieldError } from '@cf/ui';
import { useId, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { emptySku, SALE_STATUS_LABELS, type SaleStatusCode } from '@/lib/product/create';
import type { AdminProductDetail } from '@/lib/product/detail';
import {
  CANDIDATES_TOO_MANY,
  emptySkuAddForm,
  newValueProblem,
  sameSkus,
  skuAddBlocked,
  skuAddRequest,
  skuCandidates,
  skuEditRowsOf,
  SKUS_VALIDATION,
  skusRequest,
  type NewSkuForm,
  type SkuAddForm,
  type SkuEditRow,
} from '@/lib/product/options';
import { productSkusPath, underAny } from '@/lib/product/section';
import { useSectionSave } from '@/lib/product/use-section-save';
import { LOCKED_NOTICE, useReportDirty, type PanelProps } from './option-panel';
import { SectionForm } from './SectionEditFrame';

const cell = 'h-9 rounded-md border border-border bg-bg px-2';
const invalid = (messages: string[]) => (messages.length > 0 ? 'border-danger' : '');

/** 표 칸 하나 — 이름은 열 머리가 대신하므로 `aria-label` 로 줄 이름을 붙인다 */
function CellInput({
  label,
  value,
  onChange,
  errors,
  inputMode,
  className = 'w-28',
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  errors: string[];
  inputMode?: 'decimal' | 'numeric';
  className?: string;
}) {
  return (
    <div className="flex flex-col gap-1">
      <input
        aria-label={label}
        aria-invalid={errors.length > 0 || undefined}
        inputMode={inputMode}
        value={value}
        onChange={(event) => onChange(event.currentTarget.value)}
        className={`${cell} ${className} ${invalid(errors)}`}
      />
      <FieldError messages={errors} />
    </div>
  );
}

function StatusSelect({
  label,
  value,
  onChange,
  onSaleDisabled = false,
  errors = [],
}: {
  label: string;
  value: SaleStatusCode;
  onChange: (value: SaleStatusCode) => void;
  onSaleDisabled?: boolean;
  errors?: string[];
}) {
  return (
    <div className="flex flex-col gap-1">
      <select
        aria-label={label}
        value={value}
        onChange={(event) => onChange(event.currentTarget.value as SaleStatusCode)}
        className={`${cell} w-28 ${invalid(errors)}`}
      >
        {(Object.keys(SALE_STATUS_LABELS) as SaleStatusCode[]).map((code) => (
          <option key={code} value={code} disabled={code === 'ON_SALE' && onSaleDisabled}>
            {SALE_STATUS_LABELS[code]}
          </option>
        ))}
      </select>
      <FieldError messages={errors} />
    </div>
  );
}

/**
 * 기존 SKU 전체의 추가금 · 판매 상태 · 무게 · HS 코드(BE #573 `PUT .../skus`). 판매 종료는 판매 상태로 한다.
 * 무게 · HS 코드를 비우면 지운다(전체 교체). 은퇴 옵션을 쓰는 SKU 는 판매중으로 돌릴 수 없다.
 */
export function SkuEditPanel({ product, locked, onDirty, onSaved, onReloaded }: PanelProps) {
  const [rows, setRows] = useState<SkuEditRow[]>(() => skuEditRowsOf(product));
  const unchanged = sameSkus(rows, skuEditRowsOf(product));
  useReportDirty(!unchanged, onDirty);
  const section = useSectionSave({
    product,
    onSaved: (saved) => onSaved(saved, 'SKU 를 저장했습니다.'),
    onReloaded,
    isField: underAny('skus'),
    validation: SKUS_VALIDATION,
  });

  if (section.forbidden) return <AccessNotice denied="forbidden" />;
  const currency = product.salePrice.currency;
  const unit = currency === 'KRW' ? '원' : '달러';
  const patch = (index: number, value: Partial<SkuEditRow>) =>
    setRows((current) => current.map((row, at) => (at === index ? { ...row, ...value } : row)));

  return (
    <SectionForm
      section={section}
      title={`SKU ${rows.length}개`}
      description="조합마다 추가금 · 판매 상태 · 배송 무게 · HS 코드를 고칩니다. 무게 · HS 코드를 비우면 지웁니다. 판매중 SKU 중 하나는 추가금이 0 이어야 합니다."
      unchanged={unchanged}
      locked={locked}
      onSave={() =>
        void section.save(
          (version) => skusRequest(rows, currency, version),
          (request) =>
            apiFetch<AdminProductDetail>(productSkusPath(product.productId), {
              method: 'PUT',
              json: request,
            }),
        )
      }
    >
      {locked && <p className="text-sm text-muted-fg">{LOCKED_NOTICE}</p>}
      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm">
          <thead className="text-muted-fg">
            <tr>
              <th className="py-2 pr-3 font-medium">조합</th>
              <th className="py-2 pr-3 font-medium">추가금({unit})</th>
              <th className="py-2 pr-3 font-medium">판매 상태</th>
              <th className="py-2 pr-3 font-medium">무게(g)</th>
              <th className="py-2 pr-3 font-medium">HS 코드</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => {
              const at = (field: string) => section.errorsOf(`skus.${index}.${field}`);
              return (
                <tr key={row.skuId} className="border-t border-border align-top">
                  <td className="py-2 pr-3">
                    {row.label}
                    {row.retired && <span className="ml-2 text-muted-fg">(은퇴 옵션)</span>}
                  </td>
                  <td className="py-2 pr-3">
                    <CellInput
                      label={`${row.label} 추가금`}
                      inputMode="decimal"
                      value={row.additionalPrice}
                      onChange={(additionalPrice) => patch(index, { additionalPrice })}
                      errors={at('additionalPrice')}
                    />
                  </td>
                  <td className="py-2 pr-3">
                    <StatusSelect
                      label={`${row.label} 판매 상태`}
                      value={row.saleStatus}
                      onChange={(saleStatus) => patch(index, { saleStatus })}
                      onSaleDisabled={row.retired && row.saleStatus !== 'ON_SALE'}
                      errors={at('saleStatus')}
                    />
                  </td>
                  <td className="py-2 pr-3">
                    <CellInput
                      label={`${row.label} 무게`}
                      inputMode="numeric"
                      value={row.weightGrams}
                      onChange={(weightGrams) => patch(index, { weightGrams })}
                      errors={at('weightGrams')}
                      className="w-24"
                    />
                  </td>
                  <td className="py-2 pr-3">
                    <CellInput
                      label={`${row.label} HS 코드`}
                      value={row.hsCode}
                      onChange={(hsCode) => patch(index, { hsCode })}
                      errors={at('hsCode')}
                      className="w-36"
                    />
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <FieldError messages={section.errorsOf('skus')} />
    </SectionForm>
  );
}

/**
 * 옵션 값 · 새 조합 SKU 추가(BE #583 `POST .../skus`). 기존 옵션에 값을 더하고, 아직 없는 조합 중 고른 것을 SKU 로 만든다.
 * 새 옵션(그룹)은 더할 수 없다. 처음 재고를 비우면 품절로 생긴다. 같은 요청을 다시 보내면 버전이 달라 409 라 두 번 생기지 않는다.
 */
export function SkuAddPanel({ product, locked, onDirty, onSaved, onReloaded }: PanelProps) {
  const id = useId();
  const [form, setForm] = useState<SkuAddForm>(emptySkuAddForm);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [draftErrors, setDraftErrors] = useState<Record<string, string>>({});
  const dirty =
    Object.values(form.newValues).some((values) => values.length > 0) ||
    Object.keys(form.selected).length > 0;
  useReportDirty(dirty, onDirty);
  const section = useSectionSave({
    product,
    onSaved: (saved) =>
      onSaved(saved, '새 조합 SKU 를 더했습니다. 재고는 재고 화면에서 확인해 주세요.'),
    onReloaded,
    isField: underAny('skus', 'newOptionValues'),
    validation: SKUS_VALIDATION,
  });

  if (section.forbidden) return <AccessNotice denied="forbidden" />;
  const blockedReason = skuAddBlocked(product);
  const candidates = blockedReason ? [] : skuCandidates(product, form);
  const currency = product.salePrice.currency;
  const unit = currency === 'KRW' ? '원' : '달러';
  const selectedKeys = (candidates ?? [])
    .filter((item) => form.selected[item.key])
    .map((item) => item.key);

  const addValue = (groupId: string) => {
    const raw = drafts[groupId] ?? '';
    const problem = newValueProblem(product, form, groupId, raw);
    if (problem) {
      setDraftErrors((value) => ({ ...value, [groupId]: problem }));
      return;
    }
    setDraftErrors((value) => ({ ...value, [groupId]: '' }));
    setDrafts((value) => ({ ...value, [groupId]: '' }));
    setForm((value) => ({
      ...value,
      newValues: {
        ...value.newValues,
        [groupId]: [...(value.newValues[groupId] ?? []), raw.trim()],
      },
    }));
  };
  const removeValue = (groupId: string, name: string) =>
    setForm((value) => ({
      ...value,
      newValues: {
        ...value.newValues,
        [groupId]: (value.newValues[groupId] ?? []).filter((item) => item !== name),
      },
    }));
  const toggle = (key: string, checked: boolean) =>
    setForm((value) => {
      const selected = { ...value.selected };
      if (checked) selected[key] = emptySku();
      else delete selected[key];
      return { ...value, selected };
    });
  const patchSku = (key: string, patch: Partial<NewSkuForm>) =>
    setForm((value) => ({
      ...value,
      selected: { ...value.selected, [key]: { ...(value.selected[key] ?? emptySku()), ...patch } },
    }));

  return (
    <SectionForm
      section={section}
      title="옵션 값 · 조합 추가"
      description="기존 옵션에 값을 더하고, 아직 없는 조합을 골라 SKU 로 만듭니다. 새 옵션은 더할 수 없습니다 — 옵션 구조를 바꾸려면 새 상품으로 등록해 주세요."
      unchanged={!dirty}
      locked={locked || blockedReason !== null}
      saveLabel="조합 추가"
      onSave={() =>
        void section.save(
          (version) => skuAddRequest(product, form, version),
          (request) =>
            apiFetch<AdminProductDetail>(productSkusPath(product.productId), {
              method: 'POST',
              json: request,
            }),
        )
      }
    >
      {locked && <p className="text-sm text-muted-fg">{LOCKED_NOTICE}</p>}
      {blockedReason ? (
        <p className="text-sm text-muted-fg">{blockedReason}</p>
      ) : (
        <>
          {product.optionGroups.map((group) => {
            const added = form.newValues[group.optionGroupId] ?? [];
            const inputId = `${id}-${group.optionGroupId}`;
            const problem = draftErrors[group.optionGroupId];
            return (
              <div key={group.optionGroupId} className="flex flex-col gap-2">
                <label htmlFor={inputId} className="text-sm font-medium">
                  {group.name}에 값 더하기
                </label>
                <p className="text-xs text-muted-fg">
                  지금 값:{' '}
                  {group.values
                    .map((value) => (value.retiredAt ? `${value.name}(은퇴)` : value.name))
                    .join(', ')}
                </p>
                <div className="flex flex-wrap items-center gap-2">
                  <input
                    id={inputId}
                    value={drafts[group.optionGroupId] ?? ''}
                    onChange={(event) => {
                      const value = event.currentTarget.value;
                      setDrafts((current) => ({ ...current, [group.optionGroupId]: value }));
                    }}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter') addValue(group.optionGroupId);
                    }}
                    aria-invalid={problem ? true : undefined}
                    className={`${cell} h-10 w-48`}
                  />
                  <Button variant="secondary" onClick={() => addValue(group.optionGroupId)}>
                    값 더하기
                  </Button>
                  {added.map((name) => (
                    <button
                      key={name}
                      type="button"
                      onClick={() => removeValue(group.optionGroupId, name)}
                      className="rounded-md bg-muted px-2 py-1 text-sm"
                      aria-label={`새 값 ${name} 빼기`}
                    >
                      {name} ✕
                    </button>
                  ))}
                </div>
                <FieldError messages={problem ? [problem] : []} />
              </div>
            );
          })}

          {candidates === null ? (
            <p className="text-sm text-danger">{CANDIDATES_TOO_MANY}</p>
          ) : candidates.length === 0 ? (
            <p className="text-sm text-muted-fg">
              더할 수 있는 조합이 없습니다. 위에서 값을 더하면 새 조합이 생깁니다.
            </p>
          ) : (
            <fieldset className="flex flex-col gap-2">
              <legend className="mb-1 text-sm font-medium">
                더할 조합 (아직 없는 조합 {candidates.length}개)
              </legend>
              {candidates.map((candidate) => {
                const sku = form.selected[candidate.key];
                const index = selectedKeys.indexOf(candidate.key);
                const at = (field: string) => section.errorsOf(`skus.${index}.${field}`);
                return (
                  <div
                    key={candidate.key}
                    className="flex flex-col gap-2 border-t border-border py-2"
                  >
                    <Checkbox
                      label={`${candidate.label}${candidate.usesNew ? ' — 새 값' : ''}`}
                      checked={Boolean(sku)}
                      onChange={(event) => toggle(candidate.key, event.currentTarget.checked)}
                    />
                    {sku && (
                      <div className="flex flex-wrap items-start gap-3 pl-6 text-sm">
                        <CellInput
                          label={`${candidate.label} 추가금(${unit})`}
                          inputMode="decimal"
                          value={sku.additionalPrice}
                          onChange={(additionalPrice) =>
                            patchSku(candidate.key, { additionalPrice })
                          }
                          errors={at('additionalPrice')}
                        />
                        <StatusSelect
                          label={`${candidate.label} 판매 상태`}
                          value={sku.saleStatus}
                          onChange={(saleStatus) => patchSku(candidate.key, { saleStatus })}
                          errors={at('saleStatus')}
                        />
                        <CellInput
                          label={`${candidate.label} 무게(g)`}
                          inputMode="numeric"
                          value={sku.weightGrams}
                          onChange={(weightGrams) => patchSku(candidate.key, { weightGrams })}
                          errors={at('weightGrams')}
                          className="w-24"
                        />
                        <CellInput
                          label={`${candidate.label} HS 코드`}
                          value={sku.hsCode}
                          onChange={(hsCode) => patchSku(candidate.key, { hsCode })}
                          errors={at('hsCode')}
                          className="w-32"
                        />
                        <select
                          aria-label={`${candidate.label} 재고 관리`}
                          value={sku.stockMode}
                          onChange={(event) =>
                            patchSku(candidate.key, {
                              stockMode: event.currentTarget.value as NewSkuForm['stockMode'],
                            })
                          }
                          className={`${cell} w-32`}
                        >
                          <option value="quantity">수량 관리</option>
                          <option value="unlimited">무제한 판매</option>
                        </select>
                        {sku.stockMode === 'quantity' && (
                          <CellInput
                            label={`${candidate.label} 처음 재고(비우면 품절)`}
                            inputMode="numeric"
                            value={sku.quantity}
                            onChange={(quantity) => patchSku(candidate.key, { quantity })}
                            errors={at('initialStock')}
                            className="w-24"
                          />
                        )}
                      </div>
                    )}
                  </div>
                );
              })}
            </fieldset>
          )}
          <p className="text-xs text-muted-fg">
            칸 순서: 추가금 · 판매 상태 · 무게(g) · HS 코드 · 재고 관리 · 처음 재고. 처음 재고를
            비우면 품절로 생깁니다. 아무 조합에도 쓰지 않은 새 값은 저장되지 않습니다.
          </p>
          <FieldError messages={section.errorsOf('skus')} />
          <FieldError messages={section.errorsOf('newOptionValues')} />
        </>
      )}
    </SectionForm>
  );
}
