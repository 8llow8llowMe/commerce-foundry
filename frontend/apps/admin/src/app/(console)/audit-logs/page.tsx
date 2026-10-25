import type { Metadata } from 'next';
import { AuditLogList } from '@/components/audit/AuditLogList';
import { parseAuditLogQuery } from '@/lib/audit/query';

export const metadata: Metadata = { title: '관리자 감사 로그' };

/** 어드민 관리자 감사 로그(#320). 조건은 주소가 정본이다 — 허용 값만 읽고 나머지는 "전체" 다 */
export default async function AuditLogsPage({ searchParams }: PageProps<'/audit-logs'>) {
  return <AuditLogList query={parseAuditLogQuery(await searchParams)} />;
}
