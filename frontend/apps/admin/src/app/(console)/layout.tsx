import type { ReactNode } from 'react';
import { AdminShell } from '@/components/AdminShell';

/** 콘솔 화면(로그인 화면 밖)은 모두 같은 화면 틀을 쓴다. 로그인 화면은 #29 에서 이 그룹 밖에 둔다 */
export default function ConsoleLayout({ children }: { children: ReactNode }) {
  return <AdminShell>{children}</AdminShell>;
}
