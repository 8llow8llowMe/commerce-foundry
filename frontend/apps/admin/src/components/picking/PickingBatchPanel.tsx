'use client';

import { apiFetch } from '@cf/api-client';
import { Button, FieldError } from '@cf/ui';
import { useRouter } from 'next/navigation';
import { useId, useRef, useState } from 'react';
import type { AccessDenied } from '@/lib/api-errors';
import type { OrderListItem } from '@/lib/order/types';
import {
  BATCH_ORDER_MAX,
  PICKING_BATCHES_PATH,
  pickingHref,
  type PickingBatch,
} from '@/lib/picking/types';
import { createFailure } from '@/lib/picking/workbench';

/**
 * 고른 주문으로 출고 묶음을 만들고 작업대로 간다(#364, `POST .../picking-batches`).
 * 담을 수 있는지(상품 준비중 · 국내 · 일반 주문)는 서버가 정하고, 빠진 주문과 이유는 작업대에서 보인다.
 * 같은 주문으로 다시 만들면 새 묶음이 생긴다 — 보내는 중 두 번째 클릭은 막고, 결과를 모르는 실패는 자동으로 다시 보내지 않는다.
 */
export function PickingBatchPanel({
  selected,
  onDenied,
}: {
  selected: OrderListItem[];
  onDenied: (denied: AccessDenied) => void;
}) {
  const id = useId();
  const router = useRouter();
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inFlight = useRef(false);
  const tooMany = selected.length > BATCH_ORDER_MAX;

  const create = async () => {
    if (inFlight.current || selected.length === 0 || tooMany) return;
    inFlight.current = true;
    setSending(true);
    setError(null);
    try {
      const batch = await apiFetch<PickingBatch>(PICKING_BATCHES_PATH, {
        method: 'POST',
        json: { orderIds: selected.map((order) => order.orderId) },
      });
      router.push(pickingHref(batch.batchId));
      // 이동하는 동안 버튼을 막아 둔다 — 화면이 바뀌기 전 다시 눌러 새 묶음이 생기지 않게
      return;
    } catch (cause) {
      const failure = createFailure(cause);
      if (failure.kind === 'access') onDenied(failure.denied);
      else if (failure.kind === 'message' || failure.kind === 'reload') setError(failure.message);
    }
    inFlight.current = false;
    setSending(false);
  };

  return (
    <section
      aria-labelledby={`${id}-title`}
      data-tour="order-picking"
      className="flex flex-col gap-3 rounded-md border border-border p-4"
    >
      <div className="flex flex-col gap-1">
        <h2 id={`${id}-title`} className="font-medium">
          피킹 · 포장 검수
        </h2>
        <p className="text-sm text-muted-fg">
          {selected.length > 0
            ? `고른 주문 ${selected.length}건으로 출고 묶음을 만듭니다.`
            : '표에서 출고할 주문을 고르면 출고 묶음을 만들 수 있습니다.'}{' '}
          한 번에 {BATCH_ORDER_MAX}건까지 담고, 상품 준비중인 국내 일반 주문만 담깁니다. 나머지는
          작업대에 빠진 이유가 보입니다.
        </p>
      </div>
      <div>
        <Button
          onClick={() => void create()}
          disabled={sending || selected.length === 0 || tooMany}
        >
          {sending ? '만드는 중…' : '출고 묶음 만들기'}
        </Button>
      </div>
      <FieldError
        messages={[
          ...(tooMany ? [`출고 묶음에는 ${BATCH_ORDER_MAX}건까지 담을 수 있습니다.`] : []),
          ...(error ? [error] : []),
        ]}
      />
    </section>
  );
}
