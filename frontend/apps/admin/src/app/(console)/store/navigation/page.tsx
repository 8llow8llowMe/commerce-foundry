import type { Metadata } from 'next';
import { StoreNavigationEditor } from '@/components/store/StoreNavigationEditor';

export const metadata: Metadata = { title: '메뉴' };

/** 쇼핑몰 설정 > 메뉴(#602). 스토어프론트 헤더 메뉴의 초안 저장과 게시를 나눈다 */
export default function StoreNavigationPage() {
  return <StoreNavigationEditor />;
}
