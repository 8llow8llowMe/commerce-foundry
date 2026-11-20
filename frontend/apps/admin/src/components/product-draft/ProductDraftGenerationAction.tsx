'use client';

import { apiFetch } from '@cf/api-client';
import { Button } from '@cf/ui';
import { useRouter } from 'next/navigation';
import { useRef, useState, useTransition } from 'react';
import type { ProductDraft } from '@/lib/product-draft/detail';
import { generationFailure, STILL_GENERATING } from '@/lib/product-draft/errors';
import { productDraftPath } from '@/lib/product-draft/form';

const ACTIONS = {
  generate: { path: 'generate', label: '생성 시작', busy: '시작하는 중…' },
  refresh: { path: 'refresh', label: '결과 확인', busy: '확인하는 중…' },
} as const;

/**
 * 생성 시작(`POST .../{draftId}/generate`, `REQUESTED`) · 결과 확인(`POST .../{draftId}/refresh`, `GENERATING`) — #360 · BE #359 B1 · B2b.
 * 둘 다 응답이 초안이지만 화면은 서버 컴포넌트라 성공하면 다시 읽는다. 생성 시작은 같은 회차 재시도이고(이미 생성 중이면 200)
 * 결과 확인은 작업 결과를 가져와 반영할 뿐이라, 결과를 모르는 실패 뒤 다시 눌러도 생성이 두 번 걸리지 않는다.
 * 보내는 중 두 번째 클릭은 ref 로 막는다.
 */
export function ProductDraftGenerationAction({
  draftId,
  action,
}: {
  draftId: string;
  action: keyof typeof ACTIONS;
}) {
  const router = useRouter();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [refreshing, startRefresh] = useTransition();
  const sending = useRef(false);
  const { path, label, busy: busyLabel } = ACTIONS[action];

  const send = async () => {
    if (sending.current) return;
    sending.current = true;
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      const draft = await apiFetch<ProductDraft>(`${productDraftPath(draftId)}/${path}`, {
        method: 'POST',
      });
      // 결과 확인인데 아직 생성 중이면 화면이 그대로라 눌렀는지 모른다 — 안내를 남긴다
      if (action === 'refresh' && draft.status.code === 'GENERATING') setNotice(STILL_GENERATING);
      else startRefresh(() => router.refresh());
    } catch (cause) {
      const failure = generationFailure(cause, action);
      setError(failure.message);
      if (failure.refresh) startRefresh(() => router.refresh());
    } finally {
      sending.current = false;
      setSaving(false);
    }
  };

  return (
    <div className="flex flex-col gap-2">
      <div>
        <Button disabled={saving || refreshing} onClick={() => void send()}>
          {saving ? busyLabel : label}
        </Button>
      </div>
      {notice && (
        <p role="status" className="text-sm text-muted-fg">
          {notice}
        </p>
      )}
      {error && (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}
    </div>
  );
}
