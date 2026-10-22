import type { Metadata } from 'next';
import { CategoryManager } from '@/components/category/CategoryManager';

export const metadata: Metadata = { title: '카테고리' };

export default function CategoriesPage() {
  return <CategoryManager />;
}
