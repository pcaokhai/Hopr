package com.pcaokhai.urlshortenerservice.unit_tests.urlshort.application;

import com.pcaokhai.urlshortenerservice.urlshort.application.KeyGenResolver;
import com.pcaokhai.urlshortenerservice.web.keygen.KeyGenClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class KeyGenResolverTest {
    @Mock
    KeyGenClient keyGenClient;

    KeyGenResolver keyGenResolver;

    @BeforeEach
    void setUp() {
        keyGenResolver = new KeyGenResolver(keyGenClient);
    }

    @Test
    void resolveShortKey_shouldCallKeyGen() {
        String generated = "gen123";
        when(keyGenClient.generateKey()).thenReturn(generated);
        String result = keyGenResolver.resolveShortKey();
        assertEquals(generated, result);
        verify(keyGenClient).generateKey();
    }

}
