'use client';

import { cx, type NavItem, type NavLink } from '@cf/ui';
import { useId, useRef, useState } from 'react';
import { LanguageLinks, type LanguageLink } from '@/components/i18n/LanguageLinks';
import { Link } from '@/components/i18n/Link';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { CloseIcon, MenuIcon } from './HeaderIcons';
import { LogoutButton } from './LogoutButton';
import { NavAnchor } from './NavAnchor';

export type MenuPanelProps = {
  brandName: string;
  items: NavItem[];
  labels: Dictionary['header'];
  languages: LanguageLink[];
  signedIn: boolean;
};

const ACCOUNT_LINK = 'hover:text-primary disabled:opacity-50';

/**
 * 대제목 아래 하위 링크(게시 메뉴 #602). 3단계는 한 칸 들여쓴 목록이다. 긴 표시명은 줄바꿈한다(말줄임 없음).
 * 링크 없는 묶음 제목은 글자만, 다른 사이트는 새 창이다(`NavAnchor`).
 */
function SubLinks({
  links,
  newWindowLabel,
  onNavigate,
  className,
}: {
  links: NavLink[];
  newWindowLabel: string;
  onNavigate: () => void;
  className: string;
}) {
  return (
    <ul className={className}>
      {links.map((link, index) => (
        <li key={`${index}-${link.href}`} className="flex flex-col gap-3">
          <NavAnchor
            href={link.href}
            external={link.external}
            newWindowLabel={newWindowLabel}
            onClick={onNavigate}
            className={cx(
              'break-words',
              link.href === null ? 'text-muted-fg' : 'hover:text-primary',
            )}
          >
            {link.label}
          </NavAnchor>
          {link.children && (
            <SubLinks
              links={link.children}
              newWindowLabel={newWindowLabel}
              onNavigate={onNavigate}
              className="ml-4 flex flex-col gap-3"
            />
          )}
        </li>
      ))}
    </ul>
  );
}

/**
 * 햄버거 버튼 + 전체 메뉴 패널. 메뉴 아래에 계정 줄(로그인 · 회원가입 / 마이페이지 · 로그아웃)을 둔다.
 * 네이티브 모달 <dialog>(showModal)를 쓴다 — 바깥을 inert 로 만들어 포커스를 가두고, ESC 로 닫히고,
 * 닫히면 연 버튼으로 포커스가 돌아간다. 직접 포커스 트랩을 만들지 않는다.
 */
export function MenuPanel({ brandName, items, labels, languages, signedIn }: MenuPanelProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const titleId = `${panelId}-title`;

  const openPanel = () => {
    dialogRef.current?.showModal();
    setOpen(true);
  };
  const closePanel = () => dialogRef.current?.close();

  return (
    <>
      <button
        type="button"
        className="-ml-2 inline-flex size-10 items-center justify-center"
        aria-label={labels.openMenu}
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={openPanel}
      >
        <MenuIcon />
      </button>

      <dialog
        ref={dialogRef}
        id={panelId}
        aria-labelledby={titleId}
        // ESC · close() 모두 close 이벤트를 낸다
        onClose={() => setOpen(false)}
        className="m-0 h-dvh max-h-none w-full max-w-none bg-bg text-fg backdrop:bg-fg/30 lg:bottom-auto lg:h-auto"
      >
        <div className="mx-auto max-w-screen-xl px-4 pb-12 lg:px-8">
          <div className="grid h-14 grid-cols-3 items-center">
            <button
              type="button"
              className="-ml-2 inline-flex size-10 items-center justify-center"
              aria-label={labels.closeMenu}
              onClick={closePanel}
            >
              <CloseIcon />
            </button>
            <p
              id={titleId}
              className="text-center font-display text-xl tracking-[0.25em] uppercase"
            >
              <span className="sr-only">{labels.menu} — </span>
              {brandName}
            </p>
          </div>

          <nav aria-label={labels.menu} className="mt-8 lg:mt-10">
            <ul className="grid grid-cols-1 gap-x-12 sm:grid-cols-2">
              {items.map((item, index) => (
                <li key={`${index}-${item.href}`} className={item.children ? 'pb-5' : undefined}>
                  <NavAnchor
                    href={item.href}
                    external={item.external}
                    newWindowLabel={labels.newWindow}
                    onClick={closePanel}
                    className="group flex flex-col gap-1 py-5 focus-visible:outline-offset-4"
                  >
                    <span className="text-sm font-medium tracking-[0.2em] break-words uppercase group-hover:text-primary">
                      {item.label}
                    </span>
                    {item.description && (
                      <span className="text-sm text-muted-fg">{item.description}</span>
                    )}
                  </NavAnchor>
                  {item.children && (
                    <SubLinks
                      links={item.children}
                      newWindowLabel={labels.newWindow}
                      onNavigate={closePanel}
                      className="flex flex-col gap-3 text-sm"
                    />
                  )}
                </li>
              ))}
            </ul>
          </nav>

          <nav aria-label={labels.accountNav} className="mt-12">
            <ul className="flex gap-8 text-sm tracking-[0.15em]">
              {signedIn ? (
                <>
                  <li>
                    <Link href="/mypage" onClick={closePanel} className={ACCOUNT_LINK}>
                      {labels.account}
                    </Link>
                  </li>
                  <li>
                    <LogoutButton onDone={closePanel} className={ACCOUNT_LINK}>
                      {labels.logout}
                    </LogoutButton>
                  </li>
                </>
              ) : (
                <>
                  <li>
                    <Link href="/login" onClick={closePanel} className={ACCOUNT_LINK}>
                      {labels.login}
                    </Link>
                  </li>
                  <li>
                    <Link href="/signup" onClick={closePanel} className={ACCOUNT_LINK}>
                      {labels.signUp}
                    </Link>
                  </li>
                </>
              )}
            </ul>
          </nav>

          <LanguageLinks languages={languages} label={labels.switchLanguage} className="mt-8" />
        </div>
      </dialog>
    </>
  );
}
