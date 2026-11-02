'use client';

import { apiFetch } from '@cf/api-client';
import { Button } from '@cf/ui';
import { useRouter } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { adminProductPath } from '@/lib/product/detail';
import { productDeletedHref, productDeleteFailure } from '@/lib/product/delete';

/**
 * 상품 상세의 "판매 중지 · 삭제"(#29, BE #9 `DELETE /api/v1/admin/products/{productId}`).
 * 되돌릴 수 없어 한 번 더 묻는다. 요청 중에는 다시 보내지 않는다(ref — 상태 반영 전 두 번째 클릭도 막는다).
 * 성공하면 상세가 404 가 되므로 목록으로 가서 결과를 알린다. 충돌(409) · 결과 모름(502 · 504)은 화면을 다시 읽고 문구를 남긴다.
 * 포커스 — 확인이 열리면 "취소" 로, 닫히면(취소 · 다시 읽기) 여는 버튼으로 돌린다(카테고리 삭제와 같다).
 */
export function ProductDeleteAction({
  productId,
  productName,
}: {
  productId: string;
  productName: string;
}) {
  const router = useRouter();
  const sending = useRef(false);
  const [confirming, setConfirming] = useState(false);
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  // Button 은 ref 를 받지 않아 카테고리 화면처럼 `data-focus` 로 찾는다
  const sectionRef = useRef<HTMLElement>(null);
  // 열림 · 닫힘이 바뀔 때만 옮긴다 — 첫 마운트에는 옮기지 않는다
  const wasConfirming = useRef(false);
  useEffect(() => {
    if (confirming === wasConfirming.current) return;
    wasConfirming.current = confirming;
    const target = confirming ? 'cancel' : 'open';
    sectionRef.current?.querySelector<HTMLElement>(`[data-focus="${target}"]`)?.focus();
  }, [confirming]);

  const remove = async () => {
    if (sending.current) return;
    sending.current = true;
    setPending(true);
    setMessage(null);
    let leaving = false;
    try {
      await apiFetch<null>(adminProductPath(productId), { method: 'DELETE' });
      leaving = true;
      router.replace(productDeletedHref('done'));
    } catch (cause) {
      const failure = productDeleteFailure(cause);
      if (failure.kind === 'forbidden') setForbidden(true);
      else if (failure.kind === 'gone') {
        leaving = true;
        router.replace(productDeletedHref('gone'));
      } else {
        setMessage(failure.message);
        if (failure.kind === 'reload') {
          setConfirming(false);
          router.refresh();
        }
      }
    } finally {
      // 목록으로 떠나는 중에는 버튼을 다시 열지 않는다 — 이동이 끝날 때까지 두 번째 요청을 막는다
      if (!leaving) {
        sending.current = false;
        setPending(false);
      }
    }
  };

  if (forbidden) return <AccessNotice denied="forbidden" />;

  return (
    <section
      ref={sectionRef}
      aria-labelledby="product-delete-heading"
      className="flex flex-col gap-3 rounded-md border border-danger/40 p-4"
    >
      <h2 id="product-delete-heading" className="text-base font-semibold text-danger">
        판매 중지 · 삭제
      </h2>
      <p className="text-sm text-muted-fg">
        상품을 숨기고 판매를 멈춘 뒤 삭제합니다. 고객 화면과 어드민 상품 목록에서 빠지고{' '}
        <strong className="font-medium text-fg">되돌릴 수 없습니다.</strong> 이미 들어온 주문 · 재고
        기록은 그대로 남습니다.
      </p>
      {confirming ? (
        <div role="group" aria-label="삭제 확인" className="flex flex-col gap-3">
          <p className="text-sm font-medium">‘{productName}’ 을(를) 판매 중지하고 삭제할까요?</p>
          <div className="flex flex-wrap gap-3">
            {/* 요청 중에도 disabled 로 두지 않는다 — 포커스가 body 로 빠진다. 겹친 요청은 remove 가 막는다 */}
            <Button aria-disabled={pending || undefined} onClick={() => void remove()}>
              {pending ? '삭제하는 중…' : '판매 중지하고 삭제'}
            </Button>
            <Button
              data-focus="cancel"
              variant="secondary"
              disabled={pending}
              onClick={() => {
                setConfirming(false);
                setMessage(null);
              }}
            >
              취소
            </Button>
          </div>
        </div>
      ) : (
        <div>
          <Button
            data-focus="open"
            variant="secondary"
            onClick={() => {
              setConfirming(true);
              setMessage(null);
            }}
          >
            판매 중지 · 삭제…
          </Button>
        </div>
      )}
      <p role="status" className="min-h-5 text-sm text-danger">
        {message}
      </p>
    </section>
  );
}
