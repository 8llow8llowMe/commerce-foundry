import type { Metadata } from 'next';
import { TemplateManager } from '@/components/size-chart/TemplateManager';

export const metadata: Metadata = { title: '사이즈표 템플릿' };

export default function SizeChartTemplatesPage() {
  return <TemplateManager />;
}
