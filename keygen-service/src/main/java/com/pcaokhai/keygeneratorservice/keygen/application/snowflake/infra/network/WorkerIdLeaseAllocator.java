/*
 * Copyright 2025 the original author.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pcaokhai.keygeneratorservice.keygen.application.snowflake.infra.network;

import com.pcaokhai.keygeneratorservice.keygen.application.snowflake.exception.WorkerIdExhaustedException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Claims a single worker ID (0-1023, covering the 5-bit datacenter and 5-bit machine ID
 * fields) for this instance by taking an exclusive, TTL-bound lease in Redis.
 *
 * A lease is claimed via {@code SET key value NX EX ttl} on startup: only one instance can
 * hold the lease key for a given worker ID at a time, so two instances starting concurrently
 * cannot end up with the same ID. The lease is renewed periodically so a live instance keeps
 * its ID; if an instance crashes without releasing it, the lease simply expires and the ID
 * becomes claimable again.
 */
@Component
public class WorkerIdLeaseAllocator {

    public static final int MAX_WORKER_ID = 1024;
    private static final String LEASE_KEY_PREFIX = "keygen:worker-lease:";
    private static final Duration LEASE_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;
    private final String instanceId = UUID.randomUUID().toString();
    private final long workerId;

    public WorkerIdLeaseAllocator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.workerId = acquire();
    }

    public long getWorkerId() {
        return workerId;
    }

    private long acquire() {
        int start = ThreadLocalRandom.current().nextInt(MAX_WORKER_ID);
        for (int offset = 0; offset < MAX_WORKER_ID; offset++) {
            int candidate = (start + offset) % MAX_WORKER_ID;
            Boolean claimed = redisTemplate.opsForValue()
                    .setIfAbsent(leaseKey(candidate), instanceId, LEASE_TTL);
            if (Boolean.TRUE.equals(claimed)) {
                return candidate;
            }
        }
        throw new WorkerIdExhaustedException("No worker IDs available in range 0-" + (MAX_WORKER_ID - 1));
    }

    @Scheduled(fixedDelay = 10_000)
    void renewLease() {
        redisTemplate.expire(leaseKey(workerId), LEASE_TTL);
    }

    private String leaseKey(long id) {
        return LEASE_KEY_PREFIX + id;
    }
}
