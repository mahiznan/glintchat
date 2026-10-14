package com.glint.gateway.socket;

/** One open WebSocket of one user on this pod. Kept as an interface so routing can be tested without a network. */
public interface ClientSocket {
    String sessionId();
    String userId();
    void send(String json);
}
