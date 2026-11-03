package com.commercefoundry.domainlayer.notification.adapter.in.event;

import com.commercefoundry.global.properties.AnomalyNotificationProperties;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

/** 전용 소비 실패는 seek + 유한 재시도 뒤 정지한다. recover / skip / DLT producer가 없다. */
@Slf4j
@RequiredArgsConstructor
public class AnomalyDltErrorHandler implements CommonErrorHandler {
    private final AnomalyNotificationProperties properties;
    private final ThreadLocal<Map<Identity, Integer>> failures = ThreadLocal.withInitial(HashMap::new);

    @Override
    public boolean seeksAfterHandling() {
        return true;
    }

    @Override
    public boolean isAckAfterHandle() {
        return false;
    }

    @Override
    public void clearThreadState() {
        failures.remove();
    }

    @Override
    public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions, Runnable publishPause) {
        failures.remove();
    }

    @Override
    public void handleRemaining(Exception exception, List<ConsumerRecord<?, ?>> records, Consumer<?, ?> consumer, MessageListenerContainer container) {
        if (records.isEmpty()) {
            stop(container, exception);
            return;
        }
        ConsumerRecord<?, ?> failed = records.getFirst();
        if (failures.get().size() >= 1024 && !failures.get().containsKey(identity(failed))) {
            stop(container, exception);
            return;
        }
        int attempts = failures.get().merge(identity(failed), 1, Integer::sum);
        try {
            Map<TopicPartition, Long> positions = new HashMap<>();
            for (ConsumerRecord<?, ?> record : records) {
                positions.merge(new TopicPartition(record.topic(), record.partition()), record.offset(), Math::min);
            }
            positions.forEach(consumer::seek);
            if (attempts >= properties.retryAttempts()) {
                stop(container, exception);
                return;
            }
            Thread.sleep(properties.retryBackoff().toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            stop(container, interrupted);
        } catch (RuntimeException seekFailure) {
            stop(container, seekFailure);
        }
    }

    @Override
    public void handleOtherException(Exception exception, Consumer<?, ?> consumer, MessageListenerContainer container, boolean batchListener) {
        stop(container, exception);
    }

    private void stop(MessageListenerContainer container, Exception exception) {
        log.error("anomaly dlt consumer stopped; offset uncommitted. causeType={}", exception.getClass().getSimpleName());
        container.stopAbnormally(() -> { });
    }

    public void succeeded(ConsumerRecord<?, ?> record) {
        failures.get().remove(identity(record));
    }

    private static Identity identity(ConsumerRecord<?, ?> record) {
        return new Identity(record.topic(), record.partition(), record.offset());
    }

    private record Identity(String topic, int partition, long offset) {
    }
}
