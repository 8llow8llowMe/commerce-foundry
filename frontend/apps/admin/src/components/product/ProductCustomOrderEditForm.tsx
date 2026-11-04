'use client';

import { apiFetch } from '@cf/api-client';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import {
  CUSTOM_ORDER_VALIDATION,
  customOrderFormOf,
  customOrderRequest,
  sameCustomOrder,
  type CustomOrderForm,
} from '@/lib/product/custom-order';
import type { AdminProductDetail } from '@/lib/product/detail';
import { productCustomOrderPath, underAny } from '@/lib/product/section';
import { useSectionSave } from '@/lib/product/use-section-save';
import { CustomOrderFields } from './CustomOrderFields';
import { SectionEditHeader, SectionForm } from './SectionEditFrame';

/**
 * 어드민 상품 맞춤 제작 설정 수정(#29 · BE #580) — 사용 여부 · 안내 · 소요일 · 입력 항목 전체 교체.
 * 저장한 항목의 키는 고칠 수 없다(주문이 키로 입력값을 남긴다) — 바꾸려면 항목을 지우고 새로 더한다.
 * 꺼도 항목은 남는다. 저장하면 상세로 옮긴다. 칸은 등록 화면과 같은 `CustomOrderFields` 다.
 */
export function ProductCustomOrderEditForm({ product: initial }: { product: AdminProductDetail }) {
  const router = useRouter();
  const [product, setProduct] = useState(initial);
  const [form, setForm] = useState<CustomOrderForm>(() => customOrderFormOf(initial));
  const section = useSectionSave({
    product,
    keepBusyAfterSave: true,
    onSaved: (saved) => router.replace(`/products/${saved.productId}`),
    onReloaded: (latest) => {
      setProduct(latest);
      setForm(customOrderFormOf(latest));
    },
    isField: (key) =>
      ['notice', 'leadTimeDays', 'enabled'].includes(key) || underAny('fields')(key),
    validation: CUSTOM_ORDER_VALIDATION,
  });

  if (section.forbidden) return <AccessNotice denied="forbidden" />;

  return (
    <div className="flex max-w-4xl flex-col gap-8">
      <SectionEditHeader
        productId={product.productId}
        productName={product.name}
        title="맞춤 제작 수정"
      >
        치수처럼 주문할 때 고객이 적는 항목을 정합니다. 이미 받은 주문의 입력값은 바뀌지 않고,
        장바구니에 담긴 입력은 주문할 때 지금 설정으로 다시 확인합니다.
      </SectionEditHeader>
      <SectionForm
        section={section}
        unchanged={sameCustomOrder(form, customOrderFormOf(product))}
        cancelHref={`/products/${product.productId}`}
        onSave={() =>
          void section.save(
            (version) => customOrderRequest(form, version),
            (request) =>
              apiFetch<AdminProductDetail>(productCustomOrderPath(product.productId), {
                method: 'PUT',
                json: request,
              }),
          )
        }
      >
        <CustomOrderFields
          form={form}
          onChange={setForm}
          errorsOf={section.errorsOf}
          busy={section.busy !== null}
          enabledLabel="맞춤 제작 상품 — 끄면 고객 화면에서 입력 항목이 사라집니다(항목은 지우지 않고 남겨 둡니다)"
        />
      </SectionForm>
    </div>
  );
}
