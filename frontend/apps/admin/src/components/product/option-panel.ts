'use client';

import { useEffect } from 'react';
import type { AdminProductDetail } from '@/lib/product/detail';

/** 옵션 · SKU 화면(`ProductOptionsEditor`)의 폼 이름 */
export type PanelName = 'names' | 'skus' | 'add' | 'retire';

/** 한 화면의 폼들이 나눠 쓰는 것. 저장 · 다시 읽기가 끝나면 모든 폼이 새 상품으로 다시 그려진다 */
export type PanelProps = {
  product: AdminProductDetail;
  /** 다른 폼을 고치는 중 — 이 폼은 잠근다(저장하면 버전이 바뀌어 다른 폼의 입력이 사라지므로 한 번에 한 폼만) */
  locked: boolean;
  onDirty: (dirty: boolean) => void;
  onSaved: (saved: AdminProductDetail, message: string) => void;
  onReloaded: (latest: AdminProductDetail) => void;
};

export const LOCKED_NOTICE =
  '다른 폼을 고치는 중입니다. 그 폼을 저장하거나 되돌린 뒤 고칠 수 있습니다.';

/** 폼의 "고치는 중" 을 부모에 알린다 */
export function useReportDirty(dirty: boolean, onDirty: (dirty: boolean) => void) {
  useEffect(() => {
    onDirty(dirty);
  }, [dirty, onDirty]);
}
