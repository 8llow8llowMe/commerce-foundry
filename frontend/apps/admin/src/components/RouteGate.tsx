'use client';

import { usePathname } from 'next/navigation';
import type { ReactNode } from 'react';
import { canAccessPath, type AdminReadPermission } from '@/lib/auth/permissions';
import { AccessNotice } from './AccessNotice';

/**
 * 권한 없는 화면에 주소로 직접 들어오면 본문 대신 403 안내를 그린다(#237). 메뉴에서 빠진 화면의 북마크 · 붙여 넣은 주소가 대상이다.
 * 보조 장치일 뿐이다 — 화면의 API 는 서버가 403 으로 막고, 권한을 모르면(null) 막지 않고 그 403 안내에 맡긴다.
 */
export function RouteGate({
  permissions,
  children,
}: {
  permissions: readonly AdminReadPermission[] | null;
  children: ReactNode;
}) {
  const pathname = usePathname();
  if (!canAccessPath(permissions, pathname)) return <AccessNotice denied="forbidden" />;
  return children;
}
