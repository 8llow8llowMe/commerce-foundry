import type { Metadata } from 'next';
import { StoreFooterEditor } from '@/components/store/StoreFooterEditor';

export const metadata: Metadata = { title: '푸터' };

/** 쇼핑몰 설정 > 푸터(#600). 채널 · 링크 · 저작권 문구의 초안 저장과 게시를 나눈다 */
export default function StoreFooterPage() {
  return <StoreFooterEditor />;
}
