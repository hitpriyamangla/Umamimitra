package com.umamimitra.link;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * What the tunnel needs from a "radio". The galley laptop is the {@link Peripheral}, the passenger laptop the {@link Central}.
 * A radio only moves small frames; everything else (CRC, retries, resend of lost frames) is in the tunnel.
 */
public final class Radio {
    private Radio() {}

    /** Galley laptop side. */
    public interface Peripheral {
        void onFrame(Consumer<byte[]> handler);
        void start() throws IOException;
        void notify(byte[] frame) throws IOException;     // hub -> passenger
        void close();
    }

    /** Passenger laptop side. */
    public interface Central {
        void onFrame(Consumer<byte[]> handler);
        void onDisconnect(Runnable handler);
        void connect() throws IOException;
        void write(byte[] frame) throws IOException;      // passenger -> hub
        int frameLimit();
        String deviceName();
        void close();
    }
}
