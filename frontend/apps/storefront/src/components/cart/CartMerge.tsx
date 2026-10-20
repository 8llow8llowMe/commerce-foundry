'use client';

import { apiFetch } from '@cf/api-client';
import { usePathname, useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { useSignedIn } from '@/components/auth/SessionProvider';
import { useDictionary } from '@/components/i18n/LocaleProvider';
import { fill } from '@/lib/i18n/format';
import { readLocalCart, removeMergedLines } from '@/lib/cart/local-cart';
import { CART_MERGED_EVENT, mergeLocalCart, type MergeResult } from '@/lib/cart/merge';
import type { CartMergeResponse } from '@/lib/cart/types';

const MERGE_LOCK = 'cf-cart-merge';
// Web Locks 가 없는 브라우저에서 같은 탭의 겹친 실행만 막는다
let inFlight = false;

/**
 * 병합은 멱등하지 않아 두 탭이 같은 로컬 줄을 동시에 보내면 두 번 더해진다. Web Locks 로 탭 사이에 한 번만 돌리고,
 * 잠금 안에서 로컬을 다시 읽는다 — 앞 탭이 지운 줄은 보내지 않는다. 잠금을 못 잡으면(다른 탭이 병합 중) 건너뛴다.
 */
function runMergeOnce(run: () => Promise<MergeResult>): Promise<MergeResult | null> {
  if (typeof navigator !== 'undefined' && 'locks' in navigator) {
    return navigator.locks.request(MERGE_LOCK, { ifAvailable: true }, (lock) =>
      lock ? run() : null,
    );
  }
  if (inFlight) return Promise.resolve(null);
  inFlight = true;
  return run().finally(() => {
    inFlight = false;
  });
}

/**
 * 로그인 상태에서 로컬(비회원) 장바구니가 남아 있으면 회원 장바구니로 보낸다(`POST /api/v1/carts/me/merge`).
 * 로그인 · 가입 직후는 물론, 서버가 처리하지 않은 실패(429 · 503 · 연결)였으면 다음 화면 진입 때 다시 보낸다 — 사용자를 막지 않는다(#91).
 * 어떤 결과를 지우고 다시 보낼지는 `mergeLocalCart` 가 정한다. 건너뛴 · 버린 줄이나 결과를 모르는 경우는 헤더 아래 한 줄로 알린다.
 */
export function CartMerge() {
  const signedIn = useSignedIn();
  const pathname = usePathname();
  const router = useRouter();
  const t = useDictionary().cart;
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    if (!signedIn || readLocalCart().length === 0) return;
    void runMergeOnce(() =>
      mergeLocalCart({
        read: readLocalCart,
        remove: removeMergedLines,
        post: (items) =>
          apiFetch<CartMergeResponse>('/api/v1/carts/me/merge', {
            method: 'POST',
            json: { items },
          }),
      }),
    ).then((result) => {
      if (!result) return;
      if (result.kind === 'signedOut') {
        router.refresh();
        return;
      }
      if (result.kind === 'merged' || result.kind === 'invalid' || result.kind === 'unknown') {
        window.dispatchEvent(new Event(CART_MERGED_EVENT));
      }
      if (result.kind === 'merged' && result.skipped > 0) {
        setNotice(fill(t.mergeSkipped, { count: result.skipped }));
      } else if (result.kind === 'invalid') {
        setNotice(fill(t.mergeSkipped, { count: result.dropped }));
      } else if (result.kind === 'unknown') {
        setNotice(t.mergeUnknown);
      }
    });
  }, [signedIn, pathname, router, t]);

  if (!notice) return null;
  return (
    <div
      role="status"
      className="mx-auto flex w-full max-w-screen-xl items-center justify-between gap-4 px-4 py-3 text-sm lg:px-8"
    >
      <span>{notice}</span>
      <button
        type="button"
        onClick={() => setNotice(null)}
        className="shrink-0 text-xs text-muted-fg underline underline-offset-4"
      >
        {t.dismiss}
      </button>
    </div>
  );
}
