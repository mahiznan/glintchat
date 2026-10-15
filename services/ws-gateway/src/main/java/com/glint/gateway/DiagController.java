package com.glint.gateway;

import com.glint.gateway.socket.SocketRegistry;
import java.lang.management.ManagementFactory;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** DEVELOPMENT ONLY: lets the failure drills see whether resources are released. Remove or protect in production. */
@RestController
public class DiagController {
    private final SocketRegistry registry;
    private final String podId;

    public DiagController(SocketRegistry registry, @Value("${gateway.pod-id}") String podId) {
        this.registry = registry;
        this.podId = podId;
    }

    @GetMapping("/internal/diag")
    public Map<String, Object> diag() {
        return Map.of("podId", podId, "sockets", registry.socketCount(), "users", registry.userCount(),
                "threads", ManagementFactory.getThreadMXBean().getThreadCount());
    }
}
