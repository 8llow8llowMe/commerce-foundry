import type { Metadata } from 'next';
import { StoreProfileEditor } from '@/components/store/StoreProfileEditor';

export const metadata: Metadata = { title: '쇼핑몰 정보' };

export default function StoreProfilePage() {
  return <StoreProfileEditor />;
}
