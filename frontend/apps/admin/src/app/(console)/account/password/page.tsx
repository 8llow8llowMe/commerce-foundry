import type { Metadata } from 'next';
import { PasswordChangeForm } from '@/components/admin-account/PasswordChangeForm';

export const metadata: Metadata = { title: '내 비밀번호 변경' };

/** 로그인한 관리자 본인의 비밀번호 변경(#352). 상단 바 계정 메뉴에서 연다 */
export default function MyPasswordPage() {
  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold">내 비밀번호 변경</h1>
        <p className="mt-1 text-sm text-muted-fg">
          바꾸면 지금 로그인이 끝나고, 새 비밀번호로 다시 로그인합니다.
        </p>
      </div>
      <PasswordChangeForm />
    </div>
  );
}
