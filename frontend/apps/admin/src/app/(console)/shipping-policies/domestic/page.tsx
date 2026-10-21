import type { Metadata } from 'next';
import { DomesticPolicyEditor } from '@/components/shipping/DomesticPolicyEditor';

export const metadata: Metadata = { title: '국내 배송비 정책' };

export default function DomesticShippingPolicyPage() {
  return <DomesticPolicyEditor />;
}
