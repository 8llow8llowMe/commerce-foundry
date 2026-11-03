'use client';

import { apiFetch, type PageResponse } from '@cf/api-client';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { LOAD_FAILED } from '@/lib/api-errors';
import { claimHref, claimsApiPath, claimsHref, DEFAULT_QUERY, PAGE_SIZE } from '@/lib/claim/query';
import type { ClaimListItem } from '@/lib/claim/types';
import { formatClaimTime } from './ClaimTable';

type Loaded =
  | { orderId: string; kind: 'ok'; data: PageResponse<ClaimListItem> }
  | { orderId: string; kind: 'failed' };

/**
 * 주문 상세의 클레임 칸 — 이 주문으로 접수된 클레임(`GET /api/v1/admin/claims?orderId=`)을 클레임 상세 링크로 보인다.
 * 읽지 못해도 주문 상세는 그대로 두고 이 칸만 안내한다(권한 없음은 주문 상세가 먼저 알린다).
 */
export function OrderClaims({ orderId }: { orderId: string }) {
  const [loaded, setLoaded] = useState<Loaded | null>(null);

  useEffect(() => {
    let alive = true;
    apiFetch<PageResponse<ClaimListItem>>(claimsApiPath({ ...DEFAULT_QUERY, orderId })).then(
      (data) => alive && setLoaded({ orderId, kind: 'ok', data }),
      () => alive && setLoaded({ orderId, kind: 'failed' }),
    );
    return () => {
      alive = false;
    };
  }, [orderId]);

  const current = loaded?.orderId === orderId ? loaded : null;
  return (
    <section aria-labelledby="order-claims" className="flex flex-col gap-2">
      <h2 id="order-claims" className="font-medium">
        클레임
      </h2>
      <OrderClaimsBody orderId={orderId} loaded={current} />
    </section>
  );
}

export function OrderClaimsBody({ orderId, loaded }: { orderId: string; loaded: Loaded | null }) {
  if (!loaded) return <p className="text-sm text-muted-fg">불러오는 중…</p>;
  if (loaded.kind === 'failed') return <p className="text-sm text-danger">{LOAD_FAILED}</p>;
  const { contents, totalElements } = loaded.data;
  if (contents.length === 0)
    return <p className="text-sm text-muted-fg">접수된 클레임이 없습니다.</p>;
  return (
    <>
      <ul className="flex flex-col gap-1 text-sm">
        {contents.map((claim) => (
          <li key={claim.claimId}>
            <Link href={claimHref(claim.claimId)} className="underline underline-offset-4">
              클레임 {claim.claimId}
            </Link>{' '}
            <span title={claim.type.description}>{claim.type.name}</span> ·{' '}
            <span title={claim.status.description}>{claim.status.name}</span>{' '}
            <span className="text-muted-fg">{formatClaimTime(claim.requestedAt)} 접수</span>
          </li>
        ))}
      </ul>
      {totalElements > PAGE_SIZE && (
        <Link
          href={claimsHref({ ...DEFAULT_QUERY, orderId })}
          className="text-sm underline underline-offset-4"
        >
          클레임 {totalElements}건 모두 보기
        </Link>
      )}
    </>
  );
}
