package com.jarvis.core.memory;

import com.jarvis.core.text.TextTools;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Decides whether something belongs in the protected lane. Errs on the side of
 * protecting: a false positive only means the value is shown on screen instead
 * of being spoken.
 */
public final class SensitiveKeys {

    private static final List<String> KEY_WORDS = Arrays.asList(
            "password", "pin", "otp", "cvv", "cvc", "passphrase", "secret",
            "aadhaar", "aadhar", "pan", "ssn", "ifsc", "upi", "mpin", "tpin", "atm"
    );

    private static final List<String> KEY_PHRASES = Arrays.asList(
            "card number", "account number", "security code", "security question",
            "recovery code", "backup code", "seed phrase", "private key", "lock code",
            "bank account", "debit card", "credit card", "passport number"
    );

    /** 12-19 digit runs look like card / account / Aadhaar numbers. */
    private static final Pattern LONG_NUMBER = Pattern.compile("(?:\\d[ -]?){12,19}");

    private SensitiveKeys() {
    }

    public static boolean isSensitiveKey(String canonicalKey) {
        String k = " " + TextTools.lower(canonicalKey) + " ";
        for (String w : KEY_WORDS) {
            if (k.contains(" " + w + " ")) return true;
        }
        for (String p : KEY_PHRASES) {
            if (k.contains(" " + p + " ")) return true;
        }
        return false;
    }

    public static boolean looksLikeSensitiveValue(String value) {
        return value != null && LONG_NUMBER.matcher(value).find();
    }
}
