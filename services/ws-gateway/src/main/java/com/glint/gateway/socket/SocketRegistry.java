package com.glint.gateway.socket;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** userId to the sockets this pod holds for that user (several tabs and devices are normal). */
@Component
public class SocketRegistry {
    private final ConcurrentHashMap<String, Set<ClientSocket>> byUser = new ConcurrentHashMap<>();

    public void register(ClientSocket socket) {
        byUser.computeIfAbsent(socket.userId(), k -> ConcurrentHashMap.newKeySet()).add(socket);
    }

    public void unregister(ClientSocket socket) {
        byUser.computeIfPresent(socket.userId(), (k, set) -> {
            set.remove(socket);
            return set.isEmpty() ? null : set;
        });
    }

    public Set<ClientSocket> socketsOf(String userId) {
        return byUser.getOrDefault(userId, Set.of());
    }
}
