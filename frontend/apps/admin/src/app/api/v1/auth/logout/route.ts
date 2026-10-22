import { createLogoutHandler } from '@cf/api-client/server';
import { bffOptions } from '@/lib/bff-options';

// access 를 막고(BE 차단 목록) 쿠키를 지운다. 어드민은 refresh 쿠키가 없어 본문 없이 부른다
export const POST = createLogoutHandler(bffOptions);
export const dynamic = 'force-dynamic';
