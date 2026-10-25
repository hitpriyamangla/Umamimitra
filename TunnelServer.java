package com.umamimitra.link;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/** Galley side. Receives requests from the passenger laptop, calls the local Java server, sends the reply back in frames. */
public final class TunnelServer {
    private final Radio.Peripheral radio;
    private final Function<Map<String, Object>, Map<String, Object>> forward;
    private final Consumer<String> log;
    private final Wire.Assembler asm = new Wire.Assembler();
    private final Map<Integer, byte[]> cache = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Set<Integer> running = Collections.synchronizedSet(new HashSet<>());
    private final Set<Integer> watching = Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tunnel-server");
        t.setDaemon(true);
        return t;
    });
    private volatile int frameSize = Wire.DEFAULT_FRAME;
    private volatile long lastSeen = 0;

    public final AtomicLong requests = new AtomicLong(), replays = new AtomicLong(), badCrc = new AtomicLong(),
                            framesIn = new AtomicLong(), bytesOut = new AtomicLong();
    private volatile String lastPath = "", lastTime = "";

    public TunnelServer(Radio.Peripheral radio, Function<Map<String, Object>, Map<String, Object>> forward, Consumer<String> log) {
        this.radio = radio;
        this.forward = forward;
        this.log = log;
        radio.onFrame(this::onFrame);
    }

    public boolean linked() { return System.currentTimeMillis() - lastSeen < 30_000; }
    public int frameSize() { return frameSize; }
    public String lastPath() { return lastPath; }
    public String lastTime() { return lastTime; }

    private void onFrame(byte[] frame) {
        lastSeen = System.currentTimeMillis();
        framesIn.incrementAndGet();
        Wire.Added a = asm.add(frame);
        int mid = a.mid;
        if (mid < 0) return;
        byte[] blob = a.blob;
        if (mid == Wire.HELLO) {
            if (blob != null && blob.length >= 2) frameSize = Math.max(Wire.DEFAULT_FRAME, ((blob[0] & 0xFF) << 8) | (blob[1] & 0xFF));
            pool.execute(this::hello);
            return;
        }
        if (mid == Wire.NACK) {
            if (blob != null && blob.length >= 2) {
                int[] n = Wire.parseNack(blob);
                byte[] reply = cache.get(n[0]);
                if (reply != null) {
                    List<byte[]> frames = Wire.splitFrames(n[0], reply, frameSize);
                    pool.execute(() -> {
                        for (int i = 1; i < n.length; i++) if (n[i] < frames.size()) sendFrame(frames.get(n[i]), false);
                    });
                }
            }
            return;
        }
        if (blob == null) {                              // a request is arriving in pieces: ask for what is missing
            if (watching.add(mid)) pool.execute(() -> watch(mid));
            return;
        }
        byte[] cached = cache.get(mid);
        if (cached != null) {                            // a retry of something we already answered
            replays.incrementAndGet();
            pool.execute(() -> send(mid, cached));
        } else if (running.add(mid)) {
            pool.execute(() -> handle(mid, blob));
        }
    }

    private void watch(int mid) {
        try {
            while (true) {
                Thread.sleep(1000);
                long last = asm.lastActivity(mid);
                long now = System.currentTimeMillis();
                if (last == 0 || now - last > 30_000) return;
                if (now - last >= 1000) {
                    for (byte[] f : Wire.nackFrames(mid, asm.missing(mid), frameSize)) sendFrame(f, false);
                }
            }
        } catch (InterruptedException ignored) {
        } finally {
            watching.remove(mid);
        }
    }

    private void hello() {
        byte[] hdr = Wire.header(Wire.HELLO, 0, 1);
        byte[] body = {'U', 'M', 'H', '1', (byte) (frameSize >> 8), (byte) frameSize};
        byte[] f = new byte[hdr.length + body.length];
        System.arraycopy(hdr, 0, f, 0, hdr.length);
        System.arraycopy(body, 0, f, hdr.length, body.length);
        sendFrame(f, false);
    }

    private void handle(int mid, byte[] blob) {
        try {
            Map<String, Object> req;
            try {
                req = Wire.unpackBlob(blob);
            } catch (RuntimeException e) {
                badCrc.incrementAndGet();
                log.accept("message " + mid + ": damaged, waiting for the passenger laptop to send again");
                return;
            }
            requests.incrementAndGet();
            String p = String.valueOf(req.getOrDefault("p", ""));
            lastPath = req.getOrDefault("m", "") + " " + (p.length() > 80 ? p.substring(0, 80) : p);
            lastTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            log.accept(lastPath);
            byte[] reply = Wire.packBlob(forward.apply(req));
            synchronized (cache) {
                cache.put(mid, reply);
                Iterator<Integer> it = cache.keySet().iterator();
                while (cache.size() > 64 && it.hasNext()) { it.next(); it.remove(); }
            }
            send(mid, reply);
        } finally {
            running.remove(mid);
        }
    }

    private void send(int mid, byte[] reply) {
        for (byte[] f : Wire.splitFrames(mid, reply, frameSize)) sendFrame(f, true);
    }

    private void sendFrame(byte[] f, boolean count) {
        try {
            radio.notify(f);
            if (count) bytesOut.addAndGet(f.length);
            Thread.sleep(12);                            // do not flood the radio
        } catch (IOException e) {
            log.accept("radio: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
