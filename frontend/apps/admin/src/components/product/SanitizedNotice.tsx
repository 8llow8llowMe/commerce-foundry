'use client';

import { useEffect } from 'react';
import { SANITIZED_PARAM } from '@/lib/product/create';

export const SANITIZED_MESSAGE =
  '상세 설명에서 허용되지 않은 태그 · 주소를 정리해 저장했습니다. 아래 상세 설명이 저장된 내용입니다.';

/**
 * 등록 · 기본 정보 수정 직후 상세 설명이 정제됐다는 안내(`descriptionHtmlSanitized`). 주소의 표시(`?sanitized=1`)는 보자마자 지운다 —
 * 새로 고침 · 링크 공유에 다시 뜨지 않게(한 번만). 안내는 이 화면에 머무는 동안 남는다.
 */
export function SanitizedNotice() {
  useEffect(() => {
    const url = new URL(window.location.href);
    if (!url.searchParams.has(SANITIZED_PARAM)) return;
    url.searchParams.delete(SANITIZED_PARAM);
    window.history.replaceState(null, '', `${url.pathname}${url.search}${url.hash}`);
  }, []);

  return (
    <p role="status" className="max-w-5xl rounded-md bg-muted px-4 py-3 text-sm">
      {SANITIZED_MESSAGE}
    </p>
  );
}
