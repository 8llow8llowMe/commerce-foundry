package com.commercefoundry.domainlayer.job.support;

import com.commercefoundry.domainlayer.job.application.port.out.JobResultStoragePort;
import com.commercefoundry.domainlayer.job.domain.model.JobResultContent;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class FakeJobResultStorage implements JobResultStoragePort {
    public final Map<String, Metadata> objects = new ConcurrentHashMap<>();
    public boolean failPut;
    public boolean failHead;
    public boolean failDelete;
    public boolean enabled = true;
    public int puts;
    public int deletes;
    public Duration ttl;
    public Duration extraExpiry = Duration.ZERO;
    private final Clock clock;

    public FakeJobResultStorage(Clock clock) { this.clock = clock; }

    public void reset() {
        objects.clear(); failPut = false; failHead = false; failDelete = false; enabled = true;
        puts = 0; deletes = 0; ttl = null; extraExpiry = Duration.ZERO;
    }

    @Override
    public void put(String key, JobResultContent content) {
        puts++;
        objects.put(key, new Metadata(content.bytes().length, content.contentType(), "verified-etag"));
        if (failPut) { throw new IllegalStateException("put unavailable"); }
    }

    @Override
    public Optional<Metadata> head(String key) {
        if (failHead) { throw new IllegalStateException("head unavailable"); }
        return Optional.ofNullable(objects.get(key));
    }

    @Override
    public Download sign(String key, Duration duration) {
        ttl = duration;
        return new Download("https://private.example.test/file?signature=SECRET", clock.instant().plus(duration).plus(extraExpiry));
    }

    @Override
    public void delete(String key) {
        deletes++;
        if (failDelete) { throw new IllegalStateException("delete unavailable"); }
        objects.remove(key);
    }

    @Override
    public Duration maximumTtl() { return Duration.ofMinutes(5); }

    @Override
    public boolean enabled() { return enabled; }
}
