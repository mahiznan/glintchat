package com.glint.chat.ids;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UuidV7Generator implements IdGenerator {
    @Override
    public UUID newId() {
        return UuidCreator.getTimeOrderedEpoch(); // UUID v7 (RFC 9562)
    }
}
