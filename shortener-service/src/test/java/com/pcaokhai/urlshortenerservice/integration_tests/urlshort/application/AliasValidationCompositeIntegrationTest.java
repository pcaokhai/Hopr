package com.pcaokhai.urlshortenerservice.integration_tests.urlshort.application;

import com.pcaokhai.common.url.model.UrlMapping;
import com.pcaokhai.common.url.repository.UrlRepository;
import com.pcaokhai.urlshortenerservice.integration_tests.urlshort.config.BaseIntegrationTest;
import com.pcaokhai.urlshortenerservice.urlshort.application.AliasValidationComposite;
import com.pcaokhai.urlshortenerservice.urlshort.application.AliasValidator;
import com.pcaokhai.urlshortenerservice.urlshort.exception.AliasInvalidFormatException;
import com.pcaokhai.urlshortenerservice.urlshort.exception.AliasNotAvailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
public class AliasValidationCompositeIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AliasValidationComposite aliasValidationComposite;

    @Autowired
    private UrlRepository urlRepository;

    @BeforeEach
    void setup() {
        urlRepository.deleteAll();
    }

    @Test
    void validate_shouldThrowInvalidFormat_forBadAlias() {
        String bad = "--badalias!";
        assertThrows(AliasInvalidFormatException.class, () ->
                aliasValidationComposite.validate(bad)
        );
    }

    @Test
    void validate_shouldThrowNotAvailable_whenAliasExists() {
        String alias = "alias123";
        urlRepository.save(new UrlMapping(alias, "https://x.com", alias));
        assertThrows(AliasNotAvailableException.class, () ->
                aliasValidationComposite.validate(alias)
        );
    }

    @Test
    void validate_shouldNotThrow_forBlankAlias() {
        assertDoesNotThrow(() ->
                aliasValidationComposite.validate("   ")
        );
    }

    @Test
    void validate_shouldNotThrow_forValidNewAlias() {
        String alias = "Valid_Alias-1";
        assertDoesNotThrow(() ->
                aliasValidationComposite.validate(alias)
        );
    }
}
