package com.MoleLaw_backend.auth.validation;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Utf8ByteLengthValidatorTest {
    private record Password(@Utf8ByteLength(max = 72) String value) {}

    @Test
    void validatesUtf8BytesRatherThanJavaCharacterCount() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertTrue(validator.validate(new Password("a".repeat(72))).isEmpty());
            assertTrue(validator.validate(new Password("한".repeat(24))).isEmpty());
            assertTrue(validator.validate(new Password("😀".repeat(18))).isEmpty());
            assertFalse(validator.validate(new Password("a".repeat(73))).isEmpty());
            assertFalse(validator.validate(new Password("한".repeat(25))).isEmpty());
            assertFalse(validator.validate(new Password("😀".repeat(19))).isEmpty());
            assertTrue(validator.validate(new Password(null)).isEmpty());
        }
    }
}
