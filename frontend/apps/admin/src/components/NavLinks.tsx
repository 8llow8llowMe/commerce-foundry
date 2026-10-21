'use client';

import { cx } from '@cf/ui';
import Link from 'next/link';
import { usePathname } from 'next/navigation';

export type NavGroup = {
  title: string;
  /** href 가 없으면 아직 없는 화면이다(준비 중으로 보인다) */
  items: { label: string; href?: string }[];
};

/** 어드민 왼쪽 메뉴. 지금 화면(경로가 같거나 그 아래)을 굵게 · 밝은 배경으로 표시한다(메뉴 바탕이 muted 다) */
export function NavLinks({ groups }: { groups: NavGroup[] }) {
  const pathname = usePathname();

  return (
    <nav aria-label="어드민 메뉴" className="flex flex-col gap-6">
      {groups.map((group) => (
        <div key={group.title} className="flex flex-col gap-1">
          <p className="px-3 text-xs font-medium text-muted-fg">{group.title}</p>
          <ul className="flex flex-col gap-0.5">
            {group.items.map((item) => {
              if (!item.href) {
                return (
                  <li key={item.label} className="px-3 py-2 text-sm text-muted-fg">
                    {item.label} <span className="text-xs">(준비 중)</span>
                  </li>
                );
              }
              const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
              return (
                <li key={item.label}>
                  <Link
                    href={item.href}
                    aria-current={active ? 'page' : undefined}
                    className={cx(
                      'block rounded-md px-3 py-2 text-sm',
                      active ? 'bg-bg font-medium text-fg' : 'text-fg hover:bg-bg',
                    )}
                  >
                    {item.label}
                  </Link>
                </li>
              );
            })}
          </ul>
        </div>
      ))}
    </nav>
  );
}
