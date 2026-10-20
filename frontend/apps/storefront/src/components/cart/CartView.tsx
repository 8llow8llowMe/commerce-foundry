'use client';

import { useEffect, useState } from 'react';
import { useSignedIn } from '@/components/auth/SessionProvider';
import { CART_MERGED_EVENT } from '@/lib/cart/merge';
import { GuestCart } from './GuestCart';
import { MemberCart } from './MemberCart';

/** 로그인 상태면 회원 장바구니, 아니면 로컬 장바구니. 로그인 직후 병합이 끝나면 회원 장바구니를 다시 읽는다 */
export function CartView() {
  const signedIn = useSignedIn();
  const [mergedCount, setMergedCount] = useState(0);

  useEffect(() => {
    const onMerged = () => setMergedCount((count) => count + 1);
    window.addEventListener(CART_MERGED_EVENT, onMerged);
    return () => window.removeEventListener(CART_MERGED_EVENT, onMerged);
  }, []);

  return signedIn ? <MemberCart refreshKey={mergedCount} /> : <GuestCart />;
}
