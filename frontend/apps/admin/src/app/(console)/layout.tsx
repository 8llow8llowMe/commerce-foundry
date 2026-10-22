import type { ReactNode } from 'react';
import { AdminShell } from '@/components/AdminShell';

/** 콘솔 화면은 모두 같은 화면 틀을 쓴다. 로그인 화면(`app/login`)은 이 그룹 밖이다 */
export default function ConsoleLayout({ children }: { children: ReactNode }) {
  return <AdminShell>{children}</AdminShell>;
}
