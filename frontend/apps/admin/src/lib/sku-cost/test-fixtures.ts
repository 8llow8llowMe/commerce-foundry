import type { SkuCostItem, SkuCurrentCosts } from './types';

export function costItem(patch: Partial<SkuCostItem> = {}): SkuCostItem {
  return {
    costId: '7001',
    costType: { code: 'GOODS', name: '매입 · 제작 원가', description: '원단 · 공임 등' },
    amount: { amount: 12000, currency: 'KRW' },
    effectiveFrom: '2026-10-01T00:00:00Z',
    reason: '10월 원단 단가 인상',
    adminMemberId: '4611686018427387001',
    createdAt: '2026-10-01T00:00:05Z',
    ...patch,
  };
}

export const packagingItem = (patch: Partial<SkuCostItem> = {}): SkuCostItem =>
  costItem({
    costId: '7002',
    costType: { code: 'PACKAGING', name: '포장 변동비', description: '박스 · 완충재' },
    amount: { amount: 0, currency: 'KRW' },
    ...patch,
  });

export const currentOf = (...costs: SkuCostItem[]): SkuCurrentCosts => ({ skuId: '901', costs });
