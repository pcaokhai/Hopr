package com.pcaokhai.keygeneratorservice.unit_tests.keygen.application.snowflake.infra.network;

import com.pcaokhai.keygeneratorservice.keygen.application.snowflake.exception.WorkerIdExhaustedException;
import com.pcaokhai.keygeneratorservice.keygen.application.snowflake.infra.network.WorkerIdLeaseAllocator;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Backs a StringRedisTemplate with a real ConcurrentHashMap so setIfAbsent behaves with the
 * same atomicity guarantee Redis' SET NX gives in production, letting these tests simulate
 * multiple keygen-service instances racing to claim a worker ID.
 */
class WorkerIdLeaseAllocatorTest {

    @Test
    void concurrentlyStartingInstances_getDistinctWorkerIds() throws Exception {
        StringRedisTemplate sharedRedis = fakeRedisBackedBy(new ConcurrentHashMap<>());
        int instanceCount = 16;
        ExecutorService pool = Executors.newFixedThreadPool(instanceCount);

        try {
            var futures = IntStream.range(0, instanceCount)
                    .<Future<Long>>mapToObj(i -> pool.submit(() -> new WorkerIdLeaseAllocator(sharedRedis).getWorkerId()))
                    .collect(Collectors.toList());

            Set<Long> workerIds = futures.stream()
                    .map(f -> {
                        try {
                            return f.get();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toSet());

            assertEquals(instanceCount, workerIds.size(), "every instance must get a distinct worker ID");
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void acquire_throwsWorkerIdExhaustedException_whenAllIdsAreLeased() {
        ConcurrentHashMap<String, String> leases = new ConcurrentHashMap<>();
        for (int i = 0; i < WorkerIdLeaseAllocator.MAX_WORKER_ID; i++) {
            leases.put("keygen:worker-lease:" + i, "already-leased");
        }
        StringRedisTemplate redis = fakeRedisBackedBy(leases);

        assertThrows(WorkerIdExhaustedException.class, () -> new WorkerIdLeaseAllocator(redis));
    }

    @SuppressWarnings("unchecked")
    private StringRedisTemplate fakeRedisBackedBy(ConcurrentHashMap<String, String> store) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        when(valueOps.setIfAbsent(any(String.class), any(String.class), any(Duration.class)))
                .thenAnswer(invocation -> store.putIfAbsent(invocation.getArgument(0), invocation.getArgument(1)) == null);
        when(redis.expire(any(String.class), any(Duration.class))).thenReturn(true);

        return redis;
    }
}
