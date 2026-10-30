'use client';

import Link from 'next/link';
import { HeaderMenu } from './HeaderMenu';
import { LogoutButton } from './LogoutButton';

/** 상단 바 계정 메뉴(#352) — 내 비밀번호 변경 · 2단계 인증(#417) · 로그아웃 */
export function AccountMenu() {
  return (
    <HeaderMenu label="계정" buttonLabel="계정 메뉴" title="계정">
      {(close) => (
        <div className="flex flex-col gap-1 p-2">
          <Link
            href="/account/password"
            onClick={close}
            className="rounded-md px-3 py-2 text-sm hover:bg-muted focus-visible:outline-2 focus-visible:outline-primary"
          >
            내 비밀번호 변경
          </Link>
          <Link
            href="/account/otp"
            onClick={close}
            className="rounded-md px-3 py-2 text-sm hover:bg-muted focus-visible:outline-2 focus-visible:outline-primary"
          >
            2단계 인증
          </Link>
          <LogoutButton inMenu />
        </div>
      )}
    </HeaderMenu>
  );
}
