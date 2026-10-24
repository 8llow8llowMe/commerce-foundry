package com.commercefoundry.global.support;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 이 프로세스의 작업 워커 아이디 — {@code {앱}:{호스트}:{pid}:{8자 난수}}. 같은 호스트 · pid 가 다시 떠도(컨테이너 재시작) 난수로 가른다.
 * 프로세스당 하나를 빈으로 만들어 워커에 주입한다 — 테스트는 고정 값을 넣는다.
 */
public record JobWorkerIdentity(String id) {

    public JobWorkerIdentity {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("워커 아이디가 비어 있습니다.");
        }
    }

    /** 길이가 {@code maxLength} 를 넘으면 호스트를 줄인다. */
    public static JobWorkerIdentity of(String applicationName, int maxLength) {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException exception) {
            host = "unknown-host";
        }
        String pid = String.valueOf(ManagementFactory.getRuntimeMXBean().getPid());
        String random = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
        int hostBudget = maxLength - applicationName.length() - pid.length() - random.length() - 3;
        if (host.length() > hostBudget) {
            host = host.substring(0, Math.max(0, hostBudget));
        }
        return new JobWorkerIdentity(applicationName + ":" + host + ":" + pid + ":" + random);
    }
}
