'use client';

import { ApiError, apiFetch } from '@cf/api-client';
import { Button } from '@cf/ui';
import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import { adminClaimPath, type ClaimDetail } from '@/lib/claim/types';
import { ClaimDetailView } from './ClaimDetailView';

type Status =
  | { kind: 'loading' }
  | { kind: 'denied'; denied: AccessDenied }
  | { kind: 'notFound' }
  | { kind: 'loadFailed' }
  | { kind: 'ready'; claim: ClaimDetail };

/**
 * 어드민 클레임 상세(#30 · BE #574) — `GET /api/v1/admin/claims/{claimId}`. 읽기 전용이다.
 * 없는 클레임(404 `ORDER_032`)과 형식이 틀린 아이디(400 `ORDER_166`)는 없는 클레임으로 안내한다.
 */
export function ClaimDetailScreen({ claimId }: { claimId: string }) {
  const [status, setStatus] = useState<Status>({ kind: 'loading' });
  /** 마지막으로 보낸 읽기 — 늦게 온 이전 응답(다시 불러오기 연타)이 최신을 덮지 않게 */
  const latest = useRef(0);

  // 상태는 응답 콜백에서만 바꾼다 — 마운트 effect 가 부른다
  const load = useCallback(() => {
    const request = ++latest.current;
    return apiFetch<ClaimDetail>(adminClaimPath(claimId)).then(
      (claim) => request === latest.current && setStatus({ kind: 'ready', claim }),
      (cause: unknown) => {
        if (request !== latest.current) return;
        const denied = accessDeniedOf(cause);
        if (denied) setStatus({ kind: 'denied', denied });
        else if (cause instanceof ApiError && (cause.kind === 'notFound' || cause.status === 400))
          setStatus({ kind: 'notFound' });
        else setStatus({ kind: 'loadFailed' });
      },
    );
  }, [claimId]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className="flex max-w-4xl flex-col gap-6">
      <div className="flex flex-col gap-1">
        <Link href="/claims" className="text-sm text-muted-fg underline underline-offset-4">
          클레임 목록
        </Link>
        <h1 className="text-xl font-semibold">클레임 상세</h1>
        {status.kind === 'ready' && (
          <p className="text-sm text-muted-fg">클레임 {status.claim.claimId}</p>
        )}
      </div>

      {status.kind === 'loading' && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {status.kind === 'denied' && <AccessNotice denied={status.denied} />}
      {status.kind === 'notFound' && (
        <div role="status" className="flex max-w-xl flex-col gap-3 rounded-md bg-muted px-5 py-6">
          <p className="font-medium">없는 클레임입니다.</p>
          <p className="text-sm text-muted-fg">
            주소가 맞는지 확인하거나 클레임 목록에서 다시 찾아 주세요.
          </p>
        </div>
      )}
      {status.kind === 'loadFailed' && (
        <div role="alert" className="flex items-center gap-4">
          <p className="text-sm text-danger">{LOAD_FAILED}</p>
          <Button
            variant="secondary"
            onClick={() => {
              setStatus({ kind: 'loading' });
              void load();
            }}
          >
            다시 불러오기
          </Button>
        </div>
      )}
      {status.kind === 'ready' && <ClaimDetailView claim={status.claim} />}
    </div>
  );
}
