import type { Metadata } from 'next';
import { AdminAccountManager } from '@/components/admin-account/AdminAccountManager';
import { parseAdminPage } from '@/lib/admin-account/query';

export const metadata: Metadata = { title: '관리자 계정' };

/** 가게 운영자 계정(#352) — 목록 · 추가 · 비활성화. 페이지는 주소가 정본이다 */
export default async function AdminsPage({ searchParams }: PageProps<'/admins'>) {
  return <AdminAccountManager page={parseAdminPage(await searchParams)} />;
}
