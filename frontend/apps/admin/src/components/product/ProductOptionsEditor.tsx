'use client';

import { apiFetch } from '@cf/api-client';
import { Checkbox, TextField } from '@cf/ui';
import { useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { GROUP_NAME_MAX, VALUE_NAME_MAX } from '@/lib/product/create';
import type { AdminProductDetail } from '@/lib/product/detail';
import {
  GROUP_NAME_EN_MAX,
  OPTIONS_VALIDATION,
  optionNamesFormOf,
  optionsRequest,
  retireBreaksZeroRule,
  retireRequest,
  retiringOnSaleSkus,
  sameOptionNames,
  type OptionGroupNameForm,
  type RetireForm,
  VALUE_NAME_EN_MAX,
} from '@/lib/product/options';
import {
  moveSlot,
  optionRetirementsPath,
  productOptionsPath,
  underAny,
} from '@/lib/product/section';
import { useSectionSave } from '@/lib/product/use-section-save';
import { LOCKED_NOTICE, useReportDirty, type PanelName, type PanelProps } from './option-panel';
import { SkuAddPanel, SkuEditPanel } from './ProductSkuPanels';
import { SectionEditHeader, SectionForm } from './SectionEditFrame';

const linkButton = 'w-fit text-sm text-muted-fg underline underline-offset-4 disabled:opacity-50';

/**
 * 어드민 상품 옵션 · SKU 수정(#29 · BE #9 후속) — 옵션 이름 · 순서, 기존 SKU, 옵션 값 · 새 조합 추가, 옵션 은퇴를 따로 저장한다.
 * 저장마다 상품 버전이 바뀌므로 한 번에 한 폼만 고칠 수 있다(고치는 중인 폼 밖은 잠근다). 저장하면 화면에 남아 새 상세로 다시 그린다.
 */
export function ProductOptionsEditor({ product: initial }: { product: AdminProductDetail }) {
  const [product, setProduct] = useState(initial);
  const [generation, setGeneration] = useState(0);
  const [dirty, setDirty] = useState<PanelName | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const replace = (next: AdminProductDetail, message: string | null) => {
    setProduct(next);
    setGeneration((value) => value + 1);
    setDirty(null);
    setNotice(message);
  };

  const panel = (name: PanelName): PanelProps & { key: string } => ({
    key: `${name}-${generation}`,
    product,
    locked: dirty !== null && dirty !== name,
    onDirty: (value) =>
      setDirty((current) => (value ? (current ?? name) : current === name ? null : current)),
    onSaved: (saved, message) => replace(saved, message),
    onReloaded: (latest) => replace(latest, null),
  });

  return (
    <div className="flex max-w-5xl flex-col gap-10">
      <SectionEditHeader
        productId={product.productId}
        productName={product.name}
        title="옵션 · SKU 수정"
      >
        아래 폼은 따로 저장합니다. 한 폼을 고치는 동안 다른 폼은 잠깁니다. 재고 수량은 재고
        화면에서, 원가는 원가 화면에서 고칩니다.
      </SectionEditHeader>
      {notice && (
        <p role="status" className="rounded-md bg-muted px-4 py-3 text-sm">
          {notice}
        </p>
      )}
      <OptionNamesPanel {...panel('names')} />
      <SkuEditPanel {...panel('skus')} />
      <SkuAddPanel {...panel('add')} />
      <RetirePanel {...panel('retire')} />
    </div>
  );
}

function OptionNamesPanel({ product, locked, onDirty, onSaved, onReloaded }: PanelProps) {
  const [groups, setGroups] = useState<OptionGroupNameForm[]>(() => optionNamesFormOf(product));
  const unchanged = sameOptionNames(groups, optionNamesFormOf(product));
  useReportDirty(!unchanged, onDirty);
  const section = useSectionSave({
    product,
    onSaved: (saved) => onSaved(saved, '옵션 이름 · 순서를 저장했습니다.'),
    onReloaded,
    isField: underAny('optionGroups'),
    validation: OPTIONS_VALIDATION,
  });

  if (section.forbidden) return <AccessNotice denied="forbidden" />;
  const busy = section.busy !== null;
  const patchGroup = (index: number, patch: Partial<OptionGroupNameForm>) =>
    setGroups((value) => value.map((group, at) => (at === index ? { ...group, ...patch } : group)));

  return (
    <SectionForm
      section={section}
      title="옵션 이름 · 순서"
      description={
        product.optionGroups.length === 0
          ? '옵션이 없는 상품입니다.'
          : '옵션과 값의 이름 · 영문 이름 · 고객에게 보이는 순서를 바꿉니다. 영문 이름은 영문 스토어에 보이고, 비워 두면 한국어 이름이 보입니다. 옵션 · 값을 더하거나 빼지는 않습니다(값 추가는 아래 "옵션 값 · 조합 추가", 빼기는 "옵션 은퇴").'
      }
      unchanged={unchanged}
      locked={locked}
      onSave={() =>
        void section.save(
          (version) => optionsRequest(groups, version),
          (request) =>
            apiFetch<AdminProductDetail>(productOptionsPath(product.productId), {
              method: 'PUT',
              json: request,
            }),
        )
      }
    >
      {locked && <p className="text-sm text-muted-fg">{LOCKED_NOTICE}</p>}
      {groups.map((group, index) => (
        <div
          key={group.optionGroupId}
          role="group"
          aria-label={`옵션 ${index + 1}`}
          className="flex flex-col gap-3 rounded-md border border-border p-4"
        >
          <div className="flex flex-wrap items-end gap-3">
            <TextField
              label={`옵션 이름 (${GROUP_NAME_MAX}자 이하)${group.retired ? ' — 은퇴' : ''}`}
              value={group.name}
              onChange={(event) => patchGroup(index, { name: event.currentTarget.value })}
              errors={section.errorsOf(`optionGroups.${index}.name`)}
              className="w-64"
            />
            <TextField
              label={`영문 이름 (선택, ${GROUP_NAME_EN_MAX}자 이하)`}
              lang="en"
              value={group.nameEn}
              onChange={(event) => patchGroup(index, { nameEn: event.currentTarget.value })}
              errors={section.errorsOf(`optionGroups.${index}.nameEn`)}
              className="w-64"
            />
            <button
              type="button"
              disabled={busy || index === 0}
              onClick={() => setGroups(moveSlot(groups, index, -1))}
              className={`${linkButton} pb-2`}
            >
              위로
            </button>
            <button
              type="button"
              disabled={busy || index === groups.length - 1}
              onClick={() => setGroups(moveSlot(groups, index, 1))}
              className={`${linkButton} pb-2`}
            >
              아래로
            </button>
          </div>
          <ul className="flex flex-col gap-2 pl-4">
            {group.values.map((value, at) => (
              <li key={value.optionValueId} className="flex flex-wrap items-end gap-3">
                <TextField
                  label={`값 ${at + 1} (${VALUE_NAME_MAX}자 이하)${value.retired ? ' — 은퇴' : ''}`}
                  value={value.name}
                  onChange={(event) => {
                    const name = event.currentTarget.value;
                    patchGroup(index, {
                      values: group.values.map((item, i) => (i === at ? { ...item, name } : item)),
                    });
                  }}
                  errors={section.errorsOf(`optionGroups.${index}.values.${at}.name`)}
                  className="w-56"
                />
                <TextField
                  label={`값 ${at + 1} 영문 (선택, ${VALUE_NAME_EN_MAX}자 이하)`}
                  lang="en"
                  value={value.nameEn}
                  onChange={(event) => {
                    const nameEn = event.currentTarget.value;
                    patchGroup(index, {
                      values: group.values.map((item, i) =>
                        i === at ? { ...item, nameEn } : item,
                      ),
                    });
                  }}
                  errors={section.errorsOf(`optionGroups.${index}.values.${at}.nameEn`)}
                  className="w-56"
                />
                <button
                  type="button"
                  disabled={busy || at === 0}
                  onClick={() => patchGroup(index, { values: moveSlot(group.values, at, -1) })}
                  className={`${linkButton} pb-2`}
                >
                  위로
                </button>
                <button
                  type="button"
                  disabled={busy || at === group.values.length - 1}
                  onClick={() => patchGroup(index, { values: moveSlot(group.values, at, 1) })}
                  className={`${linkButton} pb-2`}
                >
                  아래로
                </button>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </SectionForm>
  );
}

function RetirePanel({ product, locked, onDirty, onSaved, onReloaded }: PanelProps) {
  const [form, setForm] = useState<RetireForm>({ optionGroupIds: [], optionValueIds: [] });
  const [confirmed, setConfirmed] = useState(false);
  const picked = form.optionGroupIds.length + form.optionValueIds.length;
  useReportDirty(picked > 0, onDirty);
  const section = useSectionSave({
    product,
    onSaved: (saved) =>
      onSaved(saved, '옵션을 은퇴했습니다. 그 옵션을 쓰는 SKU 는 판매 종료됐습니다.'),
    onReloaded,
    isField: () => false,
  });

  if (section.forbidden) return <AccessNotice denied="forbidden" />;
  const active = product.optionGroups.filter((group) => group.retiredAt === null);
  if (active.length === 0) return null;

  const toggle = (key: keyof RetireForm, id: string, checked: boolean) => {
    setConfirmed(false);
    setForm((value) => ({
      ...value,
      [key]: checked ? [...value[key], id] : value[key].filter((item) => item !== id),
    }));
  };
  const stopping = retiringOnSaleSkus(product, form);
  const breaksPrice = retireBreaksZeroRule(product, form);

  return (
    <SectionForm
      section={section}
      title="옵션 은퇴"
      description="더 팔지 않을 옵션이나 값을 고객 화면에서 뺍니다. 지우지는 않아 지난 주문 · 재고 기록은 남습니다. 되돌릴 수 없습니다."
      unchanged={picked === 0}
      locked={locked}
      blocked={!confirmed}
      saveLabel="은퇴"
      onSave={() =>
        void section.save(
          (version) => retireRequest(form, version),
          (request) =>
            apiFetch<AdminProductDetail>(optionRetirementsPath(product.productId), {
              method: 'POST',
              json: request,
            }),
        )
      }
    >
      {locked && <p className="text-sm text-muted-fg">{LOCKED_NOTICE}</p>}
      {active.map((group) => {
        const wholeGroup = form.optionGroupIds.includes(group.optionGroupId);
        return (
          <fieldset key={group.optionGroupId} className="flex flex-col gap-1">
            <legend className="text-sm font-medium">{group.name}</legend>
            <Checkbox
              label={`옵션 "${group.name}" 전체 — 모든 SKU 가 판매 종료됩니다`}
              checked={wholeGroup}
              onChange={(event) =>
                toggle('optionGroupIds', group.optionGroupId, event.currentTarget.checked)
              }
            />
            <div className="flex flex-wrap gap-x-4 pl-6">
              {group.values
                .filter((value) => value.retiredAt === null)
                .map((value) => (
                  <Checkbox
                    key={value.optionValueId}
                    label={value.name}
                    disabled={wholeGroup}
                    checked={wholeGroup || form.optionValueIds.includes(value.optionValueId)}
                    onChange={(event) =>
                      toggle('optionValueIds', value.optionValueId, event.currentTarget.checked)
                    }
                  />
                ))}
            </div>
          </fieldset>
        );
      })}
      {picked > 0 && (
        <div className="flex flex-col gap-2 rounded-md bg-muted px-4 py-3 text-sm">
          <p>판매중 SKU {stopping}개가 함께 판매 종료됩니다.</p>
          {breaksPrice && (
            <p className="text-danger">
              은퇴 뒤 남는 판매중 SKU 에 추가금 0 인 SKU 가 없어 저장되지 않습니다. 가격이나 SKU
              추가금을 먼저 맞춰 주세요.
            </p>
          )}
          <Checkbox
            label="되돌릴 수 없음을 확인했습니다"
            checked={confirmed}
            onChange={(event) => setConfirmed(event.currentTarget.checked)}
          />
        </div>
      )}
    </SectionForm>
  );
}
