package org.yan.lightofshadowbackend.auth.code;

public interface CodeSender {
    void send(String target, String scene, String code);
}
