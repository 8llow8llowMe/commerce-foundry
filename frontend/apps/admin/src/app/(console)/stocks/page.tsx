import type { Metadata } from 'next';
import { ProductPicker } from '@/components/stock/ProductPicker';

export const metadata: Metadata = { title: '재고' };

export default function StocksPage() {
  return <ProductPicker />;
}
