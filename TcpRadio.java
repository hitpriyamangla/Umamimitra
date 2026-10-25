package com.umamimitra.link;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;
import java.util.function.Consumer;

/**
 * A radio made of one TCP connection that carries the same small frames a Bluetooth radio would.
 * Needs only core Java. Use it
 *  - on ONE computer for testing (optionally dropping frames to prove the resend logic works), or
 *  - on TWO laptops over any link between them: Bluetooth PAN (pair the laptops, "Join a Personal Area Network"),
 *    a hotspot, a cable, ...
 * Each frame is sent as: length (2 bytes) | frame.
 */
public final class TcpRadio {
    private TcpRadio() {}

    private static final Random RANDOM = new Random();

    private static boolean dropped(double loss) { return loss > 0 && RANDOM.nextDouble() < loss; }

    private static byte[] readFrame(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        byte[] frame = new byte[n];
        in.readFully(frame);
        return frame;
    }

    private static void writeFrame(DataOutputStream out, byte[] frame) throws IOException {
        synchronized (out) {
            out.writeShort(frame.length);
            out.write(frame);
            out.flush();
        }
    }

    /** Galley laptop. Waits for the passenger laptop to connect. */
    public static final class Peripheral implements Radio.Peripheral {
        private final int port;
        private final boolean localOnly;
        private final double loss;
        private ServerSocket server;
        private volatile DataOutputStream out;
        private volatile Consumer<byte[]> handler = f -> { };

        public Peripheral(int port, boolean localOnly, double loss) { this.port = port; this.localOnly = localOnly; this.loss = loss; }

        @Override public void onFrame(Consumer<byte[]> h) { handler = h; }

        @Override public void start() throws IOException {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(localOnly ? new InetSocketAddress(InetAddress.getLoopbackAddress(), port) : new InetSocketAddress(port));
            Thread t = new Thread(this::acceptLoop, "radio-accept");
            t.setDaemon(true);
            t.start();
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    s.setTcpNoDelay(true);
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    out = new DataOutputStream(s.getOutputStream());
                    Thread reader = new Thread(() -> {
                        try {
                            while (true) {
                                byte[] frame = readFrame(in);
                                if (!dropped(loss)) handler.accept(frame);
                            }
                        } catch (IOException ignored) { /* passenger left */ }
                    }, "radio-read");
                    reader.setDaemon(true);
                    reader.start();
                } catch (IOException e) {
                    if (server.isClosed()) return;
                }
            }
        }

        @Override public void notify(byte[] frame) throws IOException {
            DataOutputStream o = out;
            if (o != null && !dropped(loss)) writeFrame(o, frame);
        }

        @Override public void close() { try { if (server != null) server.close(); } catch (IOException ignored) { } }
    }

    /** Passenger laptop. Connects to the galley laptop. */
    public static final class Central implements Radio.Central {
        private final String host;
        private final int port, frameLimit;
        private final double loss;
        private Socket socket;
        private DataOutputStream out;
        private volatile Consumer<byte[]> handler = f -> { };
        private volatile Runnable disconnect = () -> { };

        public Central(String host, int port, int frameLimit, double loss) {
            this.host = host; this.port = port; this.frameLimit = frameLimit; this.loss = loss;
        }

        @Override public void onFrame(Consumer<byte[]> h) { handler = h; }
        @Override public void onDisconnect(Runnable h) { disconnect = h; }
        @Override public int frameLimit() { return frameLimit; }
        @Override public String deviceName() { return "UmamiMitra-Galley (" + host + ")"; }

        @Override public void connect() throws IOException {
            socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, port), 4000);
            } catch (IOException e) {
                throw new IOException("galley hub not found at " + host + ":" + port + " (is 'Galley Hub' running on the galley laptop?)", e);
            }
            socket.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
            Thread reader = new Thread(() -> {
                try {
                    while (true) {
                        byte[] frame = readFrame(in);
                        if (!dropped(loss)) handler.accept(frame);
                    }
                } catch (IOException e) {
                    disconnect.run();
                }
            }, "radio-read");
            reader.setDaemon(true);
            reader.start();
        }

        @Override public void write(byte[] frame) throws IOException {
            if (!dropped(loss)) writeFrame(out, frame);
        }

        @Override public void close() { try { if (socket != null) socket.close(); } catch (IOException ignored) { } }
    }
}
