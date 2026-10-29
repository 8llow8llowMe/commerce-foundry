import type { Metadata } from 'next';
import { LookManager } from '@/components/look/LookManager';

export const metadata: Metadata = { title: '룩' };

export default function LooksPage() {
  return <LookManager />;
}
