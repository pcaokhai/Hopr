package com.pcaokhai.keygeneratorservice.unit_tests.keygen.application.snowflake.infra.network;

import com.pcaokhai.keygeneratorservice.keygen.application.snowflake.infra.network.RedisNodeInfoProvider;
import com.pcaokhai.keygeneratorservice.keygen.application.snowflake.infra.network.WorkerIdLeaseAllocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisNodeInfoProviderTest {

    @ParameterizedTest
    @CsvSource({
            "0, 0, 0",
            "31, 0, 31",
            "32, 1, 0",
            "1023, 31, 31",
            "42, 1, 10"
    })
    void splitsWorkerIdIntoDatacenterAndMachineFields(long workerId, long expectedDatacenterId, long expectedMachineId) {
        WorkerIdLeaseAllocator allocator = Mockito.mock(WorkerIdLeaseAllocator.class);
        Mockito.when(allocator.getWorkerId()).thenReturn(workerId);

        RedisNodeInfoProvider provider = new RedisNodeInfoProvider(allocator);

        assertEquals(expectedDatacenterId, provider.getDatacenterId());
        assertEquals(expectedMachineId, provider.getMachineId());
    }
}
