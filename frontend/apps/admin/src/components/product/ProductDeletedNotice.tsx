'use client';

import { useEffect } from 'react';
import { DELETED_MESSAGES, DELETED_PARAM, type DeletedResult } from '@/lib/product/delete';

/**
 * 상세에서 상품을 삭제하고 목록으로 왔을 때의 안내(`?deleted=done|gone`, #29). `SanitizedNotice` 와 같이
 * 주소의 표시는 보자마자 지운다 — 새로 고침 · 링크 공유에 다시 뜨지 않게. 안내는 이 화면에 머무는 동안 남는다.
 */
export function ProductDeletedNotice({ result }: { result: DeletedResult }) {
  useEffect(() => {
    const url = new URL(window.location.href);
    if (!url.searchParams.has(DELETED_PARAM)) return;
    url.searchParams.delete(DELETED_PARAM);
    window.history.replaceState(null, '', `${url.pathname}${url.search}${url.hash}`);
  }, []);

  return (
    <p role="status" className="max-w-6xl rounded-md bg-muted px-4 py-3 text-sm">
      {DELETED_MESSAGES[result]}
    </p>
  );
}
