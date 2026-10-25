import type { Metadata } from 'next';
import { DisplayManager } from '@/components/display/DisplayManager';

export const metadata: Metadata = { title: '메인 전시' };

export default function MainDisplayPage() {
  return <DisplayManager />;
}
