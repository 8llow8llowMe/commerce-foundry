import { createEventStreamProxyHandler } from '@cf/api-client/server';
import { bffOptions } from '@/lib/bff-options';
import { NOTIFICATION_STREAM_PATH } from '@/lib/notification/types';

/**
 * 알림 스트림(SSE, BE #213) 전용 중계. 일반 프록시(`app/api/[...path]`)보다 구체적인 경로라 먼저 잡힌다.
 * 본문을 모으지 않고 흘리고, 브라우저가 끊으면 게이트웨이 연결도 끊는다(`createEventStreamProxyHandler`).
 */
const handler = createEventStreamProxyHandler({ ...bffOptions, path: NOTIFICATION_STREAM_PATH });

export const dynamic = 'force-dynamic';
export { handler as GET };
