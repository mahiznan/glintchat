package com.glint.chat.relay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "chat.relay.enabled", havingValue = "true", matchIfMissing = true)
public class RelayScheduler {
    private final OutboxRelay relay;

    public RelayScheduler(OutboxRelay relay) { this.relay = relay; }

    @Scheduled(fixedDelayString = "${chat.relay.poll-delay-ms:200}")
    public void poll() { relay.relayOnce(); }
}
