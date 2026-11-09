import { brand } from '@cf/brand';
import type { Metadata } from 'next';
import { PasswordResetForm } from '@/components/auth/PasswordResetForm';

// 토큰은 fragment 라 Referer 에 실리지 않지만, 이 화면에서 나가는 요청에 주소를 아예 싣지 않는다
export const metadata: Metadata = { title: '비밀번호 재설정', referrer: 'no-referrer' };

/**
 * 운영자 비밀번호 재설정(#237). 대표 관리자가 보낸 메일 링크(`/password-reset#token=…`)로 연다.
 * 로그인 화면처럼 콘솔 틀(`(console)`) 밖이고 `proxy.ts` 가 로그인 없이 연다.
 * 서버 설정 `AUTH_ADMIN_PASSWORD_RESET_URL` 이 이 화면의 HTTPS 주소여야 한다.
 */
export default function PasswordResetPage() {
  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-sm flex-col justify-center gap-8 px-4 py-16">
      <h1 className="text-center text-xl font-semibold">{brand.name} 비밀번호 재설정</h1>
      <PasswordResetForm />
    </main>
  );
}
