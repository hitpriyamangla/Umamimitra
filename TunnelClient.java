package com.umamimitra.link;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Passenger side. request() looks like a normal web call but travels over the radio. */
public final class TunnelClient {
    private static final class Pending {
        final CompletableFuture<Map<String, Object>> fut = new CompletableFuture<>();
        final List<byte[]> frames;
        volatile long active = System.currentTimeMillis(), nack = 0;
        Pending(List<byte[]> frames) { this.frames = frames; }
    }

    private final Radio.Central radio;
    private final Consumer<String> log;
    private final Wire.Assembler asm = new Wire.Assembler();
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(2);
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tunnel-client");
        t.setDaemon(true);
        return t;
    });
    private int nextId = new Random().nextInt(30000) + 1;
    private volatile long lastHub = 0;

    public final AtomicLong requests = new AtomicLong(), retries = new AtomicLong(), failed = new AtomicLong();

    public TunnelClient(Radio.Central radio, Consumer<String> log) {
        this.radio = radio;
        this.log = log;
        radio.onFrame(this::onFrame);
    }

    public boolean linked() { return System.currentTimeMillis() - lastHub < 30_000; }

    private void onFrame(byte[] frame) {
        lastHub = System.currentTimeMillis();
        Wire.Added a = asm.add(frame);
        int mid = a.mid;
        if (mid < 0) return;
        byte[] blob = a.blob;
        Pending mine = pending.get(mid);
        if (mine != null) mine.active = System.currentTimeMillis();
        if (mid == Wire.NACK && blob != null && blob.length >= 2) {
            int[] n = Wire.parseNack(blob);
            Pending p = pending.get(n[0]);
            if (p != null) pool.execute(() -> {
                for (int i = 1; i < n.length; i++) {
                    if (n[i] < p.frames.size()) writeFrame(p.frames.get(n[i]), 6);
                }
            });
            return;
        }
        if (mid == Wire.HELLO || blob == null || mine == null) return;
        try {
            Map<String, Object> res = Wire.unpackBlob(blob);
            mine.fut.complete(res);
        } catch (RuntimeException e) {
            log.accept("reply " + mid + " damaged, asking again");
        }
    }

    private void writeFrame(byte[] f, long pauseMs) {
        try {
            radio.write(f);
            Thread.sleep(pauseMs);
        } catch (IOException e) {
            log.accept("radio: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Handshake and heartbeat: tells the galley hub how big our frames can be. */
    public void hello() {
        for (byte[] f : Wire.splitFrames(Wire.HELLO, Wire.u16(radio.frameLimit()), Wire.DEFAULT_FRAME)) writeFrame(f, 0);
    }

    public Map<String, Object> request(String method, String path) throws IOException, InterruptedException {
        return request(method, path, 8, 4000);
    }

    public Map<String, Object> request(String method, String path, int attempts, long idleMs) throws IOException, InterruptedException {
        slots.acquire();
        int mid;
        synchronized (this) { nextId = nextId % 60000 + 1; mid = nextId; }
        try {
            byte[] blob = Wire.packBlob(Map.of("m", method, "p", path));
            Pending p = new Pending(Wire.splitFrames(mid, blob, radio.frameLimit()));
            pending.put(mid, p);
            requests.incrementAndGet();
            long deadline = System.currentTimeMillis() + 90_000;
            for (int attempt = 0; attempt < attempts; attempt++) {
                if (attempt > 0) {
                    retries.incrementAndGet();
                    log.accept("retry " + attempt + " for " + method + " " + (path.length() > 60 ? path.substring(0, 60) : path));
                }
                p.active = System.currentTimeMillis();
                for (byte[] f : p.frames) writeFrame(f, 6);
                while (!p.fut.isDone() && System.currentTimeMillis() - p.active < idleMs && System.currentTimeMillis() < deadline) {
                    try {
                        p.fut.get(500, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException ignored) {
                    } catch (java.util.concurrent.ExecutionException e) {
                        throw new IOException(e.getCause());
                    }
                    long last = asm.lastActivity(mid);          // a reply is arriving in pieces: ask for what is missing
                    long now = System.currentTimeMillis();
                    if (!p.fut.isDone() && last != 0 && now - last >= 1000 && now - p.nack >= 1000) {
                        p.nack = p.active = now;
                        for (byte[] f : Wire.nackFrames(mid, asm.missing(mid), radio.frameLimit())) writeFrame(f, 0);
                    }
                }
                if (p.fut.isDone()) return p.fut.getNow(null);
            }
            failed.incrementAndGet();
            throw new IOException("no answer from the galley laptop over the link");
        } finally {
            pending.remove(mid);
            slots.release();
        }
    }
}
