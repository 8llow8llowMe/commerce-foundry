import type { Metadata } from 'next';
import { OtpSettings } from '@/components/admin-account/OtpSettings';

export const metadata: Metadata = { title: '2단계 인증' };

/** 로그인한 관리자 본인의 2단계 인증(OTP) 등록 · 해제 · 복구 코드(#417). 상단 바 계정 메뉴에서 연다 */
export default function MyOtpPage() {
  return (
    <div className="flex max-w-6xl flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold">2단계 인증</h1>
        <p className="mt-1 text-sm text-muted-fg">
          로그인할 때 비밀번호에 더해 휴대폰 인증 앱의 코드로 한 번 더 확인합니다.
        </p>
      </div>
      <OtpSettings />
    </div>
  );
}
