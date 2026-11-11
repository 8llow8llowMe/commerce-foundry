'use client';

import { apiFetch } from '@cf/api-client';
import { Button } from '@cf/ui';
import { useRouter } from 'next/navigation';
import { useRef, useState, useTransition } from 'react';
import { rejectFailure } from '@/lib/product-draft/errors';
import { productDraftPath } from '@/lib/product-draft/form';

/**
 * 초안 거절(#360) — `POST /api/v1/admin/product-drafts/{draftId}/reject`. 한 번 더 확인한 뒤 보낸다.
 * 이미 거절한 초안에 다시 보내도 200 이라(서버 멱등) 결과를 모르는 실패는 다시 누르면 된다. 성공 · 상태가 바뀐 409 뒤에는 화면을 다시 읽는다.
 * 끝난 초안(적용 · 거절 · 만료)에는 이 칸을 그리지 않는다(`canReject`).
 */
export function ProductDraftRejectAction({ draftId }: { draftId: string }) {
  const router = useRouter();
  const [confirming, setConfirming] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [refreshing, startRefresh] = useTransition();
  const sending = useRef(false);

  const reject = async () => {
    if (sending.current) return;
    sending.current = true;
    setSaving(true);
    setError(null);
    try {
      await apiFetch(`${productDraftPath(draftId)}/reject`, { method: 'POST' });
      setConfirming(false);
      startRefresh(() => router.refresh());
    } catch (cause) {
      const failure = rejectFailure(cause);
      setError(failure.message);
      if (failure.refresh) {
        setConfirming(false);
        startRefresh(() => router.refresh());
      }
    } finally {
      sending.current = false;
      setSaving(false);
    }
  };

  const busy = saving || refreshing;
  return (
    <div className="flex flex-col gap-2">
      {!confirming ? (
        <div>
          <Button
            variant="secondary"
            disabled={busy}
            onClick={() => {
              setError(null);
              setConfirming(true);
            }}
          >
            초안 거절
          </Button>
        </div>
      ) : (
        <div
          role="group"
          aria-label="초안 거절 확인"
          className="flex flex-col gap-2 rounded-md bg-muted px-4 py-3"
        >
          <p className="text-sm">
            이 초안을 쓰지 않기로 할까요? 거절한 초안은 되돌릴 수 없고 상품에 적용할 수 없습니다.
            상품에는 영향이 없습니다.
          </p>
          <div className="flex flex-wrap gap-3">
            <Button disabled={busy} onClick={() => void reject()}>
              {saving ? '거절하는 중…' : '거절하기'}
            </Button>
            <Button variant="secondary" disabled={busy} onClick={() => setConfirming(false)}>
              그만두기
            </Button>
          </div>
        </div>
      )}
      {error && (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}
    </div>
  );
}
