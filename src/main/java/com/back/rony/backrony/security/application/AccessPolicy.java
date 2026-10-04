package com.back.rony.backrony.security.application;

import com.back.rony.backrony.shared.infrastructure.RonyProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Service;

/** Keeps the local token contract while preventing accidental token logging. */
@Service
public final class AccessPolicy {
    private final byte[] token;

    public AccessPolicy(RonyProperties properties) {
        token =
                (properties.accessToken() == null ? "" : properties.accessToken())
                        .getBytes(StandardCharsets.UTF_8);
    }

    public boolean required() {
        return token.length != 0;
    }

    public boolean matches(String value) {
        return required()
                && value != null
                && MessageDigest.isEqual(token, value.getBytes(StandardCharsets.UTF_8));
    }
}
