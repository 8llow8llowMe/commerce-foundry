package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.MethodExecutionContext;
import net.ttddyy.dsproxy.listener.MethodExecutionListener;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import org.hibernate.SessionFactory;

/** JDBC API 호출 계측. 네트워크 패킷/서버 문장 수가 아니며 SQL/바인딩 값은 보관하거나 출력하지 않는다. */
final class AnomalyProbeMeter implements QueryExecutionListener, MethodExecutionListener {
    private final ThreadLocal<Window> active = new ThreadLocal<>();

    <T> T excluded(java.util.function.Supplier<T> action) {
        var previous = active.get();
        active.remove();
        try {
            return action.get();
        } finally {
            if (previous != null) {
                active.set(previous);
            }
        }
    }

    Window start(SessionFactory factory) {
        if (active.get() != null) {
            throw new IllegalStateException("Nested measurement");
        }
        factory.getStatistics().clear();
        var window = new Window(factory);
        active.set(window);
        return window;
    }

    @Override
    public void beforeQuery(ExecutionInfo execution, List<QueryInfo> queries) {
    }

    @Override
    public void afterQuery(ExecutionInfo execution, List<QueryInfo> queries) {
        var window = active.get();
        if (window == null) {
            return;
        }
        for (var query : queries) {
            String sql = query.getQuery().stripLeading().toLowerCase(Locale.ROOT);
            // Hibernate SQL 주석은 이 slice에서 비활성화. 허용 테이블은 notification 하나다.
            String type = sql.startsWith("insert into operations.notification ") ? "insert"
                : sql.startsWith("update operations.notification ") ? "update"
                : sql.startsWith("select ") && sql.contains("operations.notification ") ? "select" : "other";
            long rows = execution.isBatch() ? query.getParametersList().size() : 1;
            window.increment(type + "AttemptRows", rows);
            if (execution.isSuccess()) {
                window.increment(type + "Rows", rows);
            } else {
                window.increment("failedExecutions", 1);
            }
            if (execution.isBatch()) {
                window.increment("batchParameterRows", rows);
            }
        }
    }

    @Override
    public void beforeMethod(MethodExecutionContext context) {
    }

    @Override
    public void afterMethod(MethodExecutionContext context) {
        var window = active.get();
        if (window == null) {
            return;
        }
        String method = context.getMethod().getName();
        if (List.of("prepareStatement", "addBatch", "execute", "executeQuery", "executeUpdate", "executeBatch", "executeLargeBatch").contains(method)) {
            window.increment("jdbc_" + method, 1);
        }
    }

    final class Window implements AutoCloseable {
        private final SessionFactory factory;
        private final Map<String, Long> counts = new LinkedHashMap<>();
        private final long started = System.nanoTime();
        private final long heapStart = heap();
        private final AtomicLong sampledPeak = new AtomicLong(heapStart);
        private final long allocatedStart = allocated();
        private final long gcCountStart = gcCount();
        private final long gcMillisStart = gcMillis();
        private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "anomaly-probe-heap-sampler");
            thread.setDaemon(true);
            return thread;
        });
        private boolean closed;

        Window(SessionFactory factory) {
            this.factory = factory;
            sampler.scheduleAtFixedRate(() -> sampledPeak.accumulateAndGet(heap(), Math::max), 0, 2, TimeUnit.MILLISECONDS);
        }

        void increment(String key, long delta) {
            counts.merge(key, delta, Long::sum);
        }

        Map<String, Object> snapshot() {
            var result = new LinkedHashMap<String, Object>(counts);
            var statistics = factory.getStatistics();
            result.put("elapsedNanos", System.nanoTime() - started);
            result.put("hibernatePrepare", statistics.getPrepareStatementCount());
            result.put("hibernateInsert", statistics.getEntityInsertCount());
            result.put("hibernateUpdate", statistics.getEntityUpdateCount());
            result.put("hibernateFlush", statistics.getFlushCount());
            result.put("heapStartBytes", heapStart);
            result.put("heapEndBytes", heap());
            result.put("heapSampledPeakBytes", sampledPeak.get());
            long allocation = allocated();
            result.put("threadAllocatedBytes", allocatedStart < 0 || allocation < 0 ? -1 : allocation - allocatedStart);
            result.put("gcCountDelta", gcCount() - gcCountStart);
            result.put("gcMillisDelta", gcMillis() - gcMillisStart);
            return Map.copyOf(result);
        }

        @Override
        public void close() {
            if (!closed) {
                active.remove();
                sampler.shutdownNow();
                closed = true;
            }
        }
    }

    static long value(Map<String, Object> values, String key) {
        return ((Number) values.getOrDefault(key, 0L)).longValue();
    }

    private static long heap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long allocated() {
        if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
            && bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled()) {
            return bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        }
        return -1;
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
    }
}
