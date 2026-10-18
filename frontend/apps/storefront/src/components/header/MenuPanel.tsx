'use client';

import type { NavItem } from '@cf/ui';
import Link from 'next/link';
import { useId, useRef, useState } from 'react';
import { CloseIcon, MenuIcon } from './HeaderIcons';

export type MenuPanelProps = {
  brandName: string;
  items: NavItem[];
};

/**
 * 햄버거 버튼 + 전체 메뉴 패널.
 * 네이티브 모달 <dialog>(showModal)를 쓴다 — 바깥을 inert 로 만들어 포커스를 가두고, ESC 로 닫히고,
 * 닫히면 연 버튼으로 포커스가 돌아간다. 직접 포커스 트랩을 만들지 않는다.
 */
export function MenuPanel({ brandName, items }: MenuPanelProps) {
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
        aria-label="전체 메뉴 열기"
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
              aria-label="전체 메뉴 닫기"
              onClick={closePanel}
            >
              <CloseIcon />
            </button>
            <p
              id={titleId}
              className="text-center text-base font-medium tracking-[0.3em] uppercase"
            >
              <span className="sr-only">전체 메뉴 — </span>
              {brandName}
            </p>
          </div>

          <nav aria-label="전체 메뉴" className="mt-8 lg:mt-10">
            <ul className="grid grid-cols-1 gap-x-12 sm:grid-cols-2">
              {items.map((item) => (
                <li key={item.href} className="border-t border-border">
                  <Link
                    href={item.href}
                    onClick={closePanel}
                    className="group flex flex-col gap-1 py-5 focus-visible:outline-offset-4"
                  >
                    <span className="text-sm font-medium tracking-[0.2em] uppercase group-hover:text-primary">
                      {item.label}
                    </span>
                    <span className="text-sm text-muted-fg">{item.description}</span>
                  </Link>
                </li>
              ))}
            </ul>
          </nav>
        </div>
      </dialog>
    </>
  );
}
