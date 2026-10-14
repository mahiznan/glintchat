package com.glint.chat.ids;

import java.util.UUID;

/** The only place ids are created, so the strategy (UUID v7) can change in one file. */
public interface IdGenerator {
    UUID newId();
}
