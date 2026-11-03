'use client';

import { apiFetch, formatMoney } from '@cf/api-client';
import { TextField } from '@cf/ui';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import type { AdminProductDetail } from '@/lib/product/detail';
import {
  PRICING_FIELDS,
  PRICING_VALIDATION,
  pricingFormOf,
  pricingRequest,
  samePricing,
  type PricingForm,
} from '@/lib/product/pricing';
import { productPricingPath } from '@/lib/product/section';
import { useSectionSave } from '@/lib/product/use-section-save';
import { SectionEditHeader, SectionForm } from './SectionEditFrame';

/**
 * 어드민 상품 가격 수정(#29 · BE #553) — 판매가 · 정가. 통화는 상품 통화 그대로다.
 * 저장하면 상세로 옮긴다. 충돌 · 결과 모름은 폼을 지키고 "최신 내용 불러오기" 를 낸다.
 */
export function ProductPricingEditForm({ product: initial }: { product: AdminProductDetail }) {
  const router = useRouter();
  const [product, setProduct] = useState(initial);
  const [form, setForm] = useState<PricingForm>(() => pricingFormOf(initial));
  const section = useSectionSave({
    product,
    keepBusyAfterSave: true,
    onSaved: (saved) => router.replace(`/products/${saved.productId}`),
    onReloaded: (latest) => {
      setProduct(latest);
      setForm(pricingFormOf(latest));
    },
    isField: (key) => PRICING_FIELDS.has(key),
    validation: PRICING_VALIDATION,
  });

  if (section.forbidden) return <AccessNotice denied="forbidden" />;

  const currency = product.salePrice.currency;
  const unit = currency === 'KRW' ? '원' : '달러, 센트까지';
  const set = (patch: Partial<PricingForm>) => setForm((value) => ({ ...value, ...patch }));

  return (
    <div className="flex max-w-3xl flex-col gap-8">
      <SectionEditHeader productId={product.productId} productName={product.name} title="가격 수정">
        지금 판매가 {formatMoney(product.salePrice)}
        {product.listPrice ? ` · 정가 ${formatMoney(product.listPrice)}` : ''}. 통화({currency})는
        바꿀 수 없습니다. SKU 추가금은 옵션 · SKU 수정에서 고칩니다.
      </SectionEditHeader>
      <SectionForm
        section={section}
        unchanged={samePricing(form, pricingFormOf(product))}
        cancelHref={`/products/${product.productId}`}
        onSave={() =>
          void section.save(
            (version) => pricingRequest(form, currency, version),
            (request) =>
              apiFetch<AdminProductDetail>(productPricingPath(product.productId), {
                method: 'PUT',
                json: request,
              }),
          )
        }
      >
        <div className="flex flex-wrap items-start gap-3">
          <TextField
            label={`판매가 (${unit})`}
            inputMode="decimal"
            value={form.salePrice}
            onChange={(event) => set({ salePrice: event.currentTarget.value })}
            errors={section.errorsOf('salePrice')}
            className="w-48"
            required
          />
          <TextField
            label={`정가 (선택, ${unit})`}
            inputMode="decimal"
            value={form.listPrice}
            onChange={(event) => set({ listPrice: event.currentTarget.value })}
            errors={section.errorsOf('listPrice')}
            className="w-48"
          />
        </div>
        <p className="text-xs text-muted-fg">
          정가는 할인 전 가격이고 판매가보다 높아야 합니다. 비우면 정가를 지웁니다. 판매가는 판매중
          SKU 의 가장 낮은 가격이라, 판매중 SKU 중 하나는 추가금이 0 이어야 합니다.
        </p>
      </SectionForm>
    </div>
  );
}
