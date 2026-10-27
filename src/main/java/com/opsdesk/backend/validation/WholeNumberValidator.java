package com.opsdesk.backend.validation;

import java.math.BigInteger;
import java.util.regex.Pattern;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

// The check behind @WholeNumber: digits only (no sign, no decimal point, no
// spaces, the same rule as ticket ids), and within min..max.
public class WholeNumberValidator implements ConstraintValidator<WholeNumber, String> {

    private static final Pattern DIGITS = Pattern.compile("^\\d+$");

    private BigInteger min;
    // null means no upper limit (the annotation's default), so page has no
    // maximum, as the contract says.
    private BigInteger max;

    @Override
    public void initialize(WholeNumber annotation) {
        min = BigInteger.valueOf(annotation.min());
        max = annotation.max() == Long.MAX_VALUE ? null : BigInteger.valueOf(annotation.max());
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (!DIGITS.matcher(value).matches()) {
            return false;
        }
        // BigInteger, because "page=99999999999999999999" is a whole number of
        // 1 or more, so it's valid. It just points past the last page. It must
        // not crash a Long.parseLong.
        BigInteger number = new BigInteger(value);
        return number.compareTo(min) >= 0 && (max == null || number.compareTo(max) <= 0);
    }
}
