package com.pcaokhai.keygeneratorservice.unit_tests.keygen.application.snowflake.infra.network;

import com.pcaokhai.keygeneratorservice.keygen.application.snowflake.exception.WorkerIdExhaustedException;
import com.pcaokhai.keygeneratorservice.keygen.application.snowflake.infra.network.WorkerIdLeaseAllocator;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void renewLease_extendsTtl_whenInstanceStillOwnsTheLease() throws Exception {
        ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, AtomicBoolean> ttlExtended = new ConcurrentHashMap<>();
        StringRedisTemplate redis = fakeRedisBackedBy(store, ttlExtended);
        WorkerIdLeaseAllocator allocator = new WorkerIdLeaseAllocator(redis);
        String key = "keygen:worker-lease:" + allocator.getWorkerId();

        invokeRenewLease(allocator);

        assertTrue(ttlExtended.getOrDefault(key, new AtomicBoolean(false)).get(),
                "a live instance's own lease must be renewed");
    }

    @Test
    void renewLease_doesNotExtendTtl_afterAnotherInstanceHasTakenOverTheKey() throws Exception {
        ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, AtomicBoolean> ttlExtended = new ConcurrentHashMap<>();
        StringRedisTemplate redis = fakeRedisBackedBy(store, ttlExtended);
        WorkerIdLeaseAllocator allocator = new WorkerIdLeaseAllocator(redis);
        String key = "keygen:worker-lease:" + allocator.getWorkerId();
        // Simulates the original lease expiring (e.g. a GC pause past the TTL) and a different
        // instance claiming the same worker ID via setIfAbsent before this instance wakes up.
        store.put(key, "another-instance-id");

        invokeRenewLease(allocator);

        assertFalse(ttlExtended.getOrDefault(key, new AtomicBoolean(false)).get(),
                "a stalled instance's renewal must not re-extend a lease now owned by another instance");
        assertEquals("another-instance-id", store.get(key));
    }

    private void invokeRenewLease(WorkerIdLeaseAllocator allocator) throws Exception {
        var method = WorkerIdLeaseAllocator.class.getDeclaredMethod("renewLease");
        method.setAccessible(true);
        method.invoke(allocator);
    }

    @SuppressWarnings("unchecked")
    private StringRedisTemplate fakeRedisBackedBy(ConcurrentHashMap<String, String> store) {
        return fakeRedisBackedBy(store, new ConcurrentHashMap<>());
    }

    /**
     * ttlExtended records, per lease key, whether a renewal call actually succeeded in
     * extending the TTL — the observable effect the CAS-vs-blind-expire distinction produces.
     */
    @SuppressWarnings("unchecked")
    private StringRedisTemplate fakeRedisBackedBy(ConcurrentHashMap<String, String> store,
            ConcurrentHashMap<String, AtomicBoolean> ttlExtended) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        when(valueOps.setIfAbsent(any(String.class), any(String.class), any(Duration.class)))
                .thenAnswer(invocation -> store.putIfAbsent(invocation.getArgument(0), invocation.getArgument(1)) == null);

        // A blind expire() (the pre-fix behavior) always "succeeds" regardless of ownership.
        when(redis.expire(any(String.class), any(Duration.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            ttlExtended.computeIfAbsent(key, k -> new AtomicBoolean()).set(true);
            return true;
        });

        // The CAS renewal script only extends the TTL if the caller-supplied instanceId still
        // matches the value stored under the lease key.
        when(redis.execute(any(RedisScript.class), any(List.class), any(), any()))
                .thenAnswer(invocation -> {
                    List<String> keys = invocation.getArgument(1);
                    String key = keys.get(0);
                    String expectedOwner = invocation.getArgument(2);
                    String currentValue = store.get(key);
                    boolean owns = expectedOwner.equals(currentValue);
                    if (owns) {
                        ttlExtended.computeIfAbsent(key, k -> new AtomicBoolean()).set(true);
                    }
                    return owns ? 1L : 0L;
                });

        return redis;
    }
}
