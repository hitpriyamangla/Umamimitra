package com.umamimitra.link;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The UmamiMitra link format (same as the original Python version).
 *
 * A request or reply is: JSON -> zlib -> + CRC32 (4 bytes), cut into frames:
 *     frame = msg id (2 bytes) | frame no (2) | frame count (2) | data
 */
public final class Wire {
    private Wire() {}

    /** Names of the Bluetooth LE service and characteristics (used by a real BLE radio; kept for documentation). */
    public static final String SERVICE = "6e5a0001-b5a3-f393-e0a9-e50e24dcca9e";
    public static final String RX = "6e5a0002-b5a3-f393-e0a9-e50e24dcca9e";   // central -> hub
    public static final String TX = "6e5a0003-b5a3-f393-e0a9-e50e24dcca9e";   // hub -> central

    public static final int HELLO = 0xFFFF;     // handshake + heartbeat message id
    public static final int NACK = 0xFFFE;      // "frames are missing, please send these again"
    public static final int HDR = 6;
    public static final int DEFAULT_FRAME = 20; // safe size for the default 23-byte BLE packet

    // ---------- message <-> blob ----------
    public static byte[] packBlob(Map<String, Object> obj) {
        byte[] json = MiniJson.bytes(obj);
        Deflater d = new Deflater(9);
        d.setInput(json);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        byte[] raw = out.toByteArray();
        return ByteBuffer.allocate(raw.length + 4).put(raw).putInt((int) crc(raw)).array();
    }

    public static Map<String, Object> unpackBlob(byte[] blob) {
        if (blob.length < 5) throw new IllegalArgumentException("blob too short");
        byte[] raw = java.util.Arrays.copyOf(blob, blob.length - 4);
        long expected = ByteBuffer.wrap(blob, blob.length - 4, 4).getInt() & 0xFFFFFFFFL;
        if (crc(raw) != expected) throw new IllegalArgumentException("CRC mismatch");
        Inflater inf = new Inflater();
        inf.setInput(raw);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) throw new IllegalArgumentException("truncated data");
                out.write(buf, 0, n);
            }
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("bad compressed data");
        } finally {
            inf.end();
        }
        return MiniJson.parse(out.toString(StandardCharsets.UTF_8));
    }

    public static long crc(byte[] data) {
        CRC32 c = new CRC32();
        c.update(data);
        return c.getValue();
    }

    // ---------- frames ----------
    public static byte[] header(int msgId, int seq, int total) {
        return ByteBuffer.allocate(HDR).putShort((short) msgId).putShort((short) seq).putShort((short) total).array();
    }

    public static List<byte[]> splitFrames(int msgId, byte[] blob, int frameSize) {
        int size = Math.max(1, frameSize - HDR);
        int count = Math.max(1, (blob.length + size - 1) / size);
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int from = i * size, to = Math.min(blob.length, from + size);
            ByteBuffer b = ByteBuffer.allocate(HDR + (to - from));
            b.put(header(msgId, i, count)).put(blob, from, to - from);
            frames.add(b.array());
        }
        return frames;
    }

    /** Control frames asking the other side to send these frame numbers of message `mid` again. */
    public static List<byte[]> nackFrames(int mid, List<Integer> missing, int frameSize) {
        final int limit = 3;
        int per = Math.max(1, (frameSize - HDR - 2) / 2);
        List<byte[]> out = new ArrayList<>();
        int max = Math.min(missing.size(), per * limit);
        for (int i = 0; i < max; i += per) {
            List<Integer> part = missing.subList(i, Math.min(max, i + per));
            ByteBuffer b = ByteBuffer.allocate(HDR + 2 + 2 * part.size());
            b.put(header(NACK, 0, 1)).putShort((short) mid);
            for (int seq : part) b.putShort((short) seq);
            out.add(b.array());
        }
        return out;
    }

    /** Reads the target message and frame numbers out of a complete NACK blob: result[0] = target, the rest = frame numbers. */
    public static int[] parseNack(byte[] blob) {
        int n = (blob.length - 2) / 2;
        int[] r = new int[1 + n];
        ByteBuffer b = ByteBuffer.wrap(blob);
        r[0] = b.getShort() & 0xFFFF;
        for (int i = 0; i < n; i++) r[1 + i] = b.getShort() & 0xFFFF;
        return r;
    }

    public static byte[] u16(int v) { return ByteBuffer.allocate(2).putShort((short) v).array(); }

    // ---------- collecting frames ----------
    /** Result of adding a frame: the message id (-1 = not a frame) and, when this frame completed the message, its data. */
    public static final class Added {
        public final int mid;
        public final byte[] blob;
        Added(int mid, byte[] blob) { this.mid = mid; this.blob = blob; }
    }

    /** Collects frames of many messages. Repeated frames are ignored. Thread-safe. */
    public static final class Assembler {
        private static final class Entry {
            int total; final Map<Integer, byte[]> parts = new HashMap<>(); long last;
        }
        private final Map<Integer, Entry> open = new HashMap<>();

        public synchronized Added add(byte[] frame) {
            if (frame.length < HDR) return new Added(-1, null);
            ByteBuffer b = ByteBuffer.wrap(frame);
            int mid = b.getShort() & 0xFFFF, seq = b.getShort() & 0xFFFF, total = b.getShort() & 0xFFFF;
            long now = System.currentTimeMillis();
            open.values().removeIf(e -> now - e.last > 60_000);
            Entry e = open.get(mid);
            if (e == null || e.total != total) {
                e = new Entry();
                e.total = total;
                open.put(mid, e);
            }
            e.parts.put(seq, java.util.Arrays.copyOfRange(frame, HDR, frame.length));
            e.last = now;
            if (e.parts.size() == total) {
                open.remove(mid);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                for (int i = 0; i < total; i++) {
                    byte[] part = e.parts.get(i);
                    if (part == null) return new Added(mid, null);   // bad numbering: ignore
                    out.write(part, 0, part.length);
                }
                return new Added(mid, out.toByteArray());
            }
            return new Added(mid, null);
        }

        public synchronized List<Integer> missing(int mid) {
            Entry e = open.get(mid);
            List<Integer> list = new ArrayList<>();
            if (e != null) for (int i = 0; i < e.total; i++) if (!e.parts.containsKey(i)) list.add(i);
            return list;
        }

        /** Time (ms) the last frame of this unfinished message arrived, or 0 if nothing is waiting. */
        public synchronized long lastActivity(int mid) {
            Entry e = open.get(mid);
            return e == null ? 0 : e.last;
        }
    }
}
