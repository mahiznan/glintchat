package com.glint.gateway.broker;

import com.glint.gateway.routing.EventRouter;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/**
 * One listener thread per pod queue, so events reach sockets in the order the broker holds them.
 * {@code @DependsOn} states the ordering in code: the pod queue must exist before the consumer attaches.
 */
@Component
@DependsOn("podQueueProvisioner")
public class PodEventListener {
    private final EventRouter router;

    public PodEventListener(EventRouter router) { this.router = router; }

    @JmsListener(destination = "chat.events::${gateway.pod-id}")
    public void onEvent(String payload) {
        router.route(payload);
    }
}
