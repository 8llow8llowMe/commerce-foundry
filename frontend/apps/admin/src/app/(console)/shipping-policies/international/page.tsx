import type { Metadata } from 'next';
import { InternationalPolicyEditor } from '@/components/shipping/InternationalPolicyEditor';

export const metadata: Metadata = { title: '해외 배송비 정책' };

export default function InternationalShippingPolicyPage() {
  return <InternationalPolicyEditor />;
}
