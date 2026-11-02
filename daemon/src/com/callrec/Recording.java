package com.callrec;

import android.media.AudioRecord;

import java.io.File;

/**
 * One call recording. Pulls 20 ms frames from a "clock" source (blocking) and mixes in a
 * second source read non-blockingly through a small FIFO, then encodes to AAC.
 */
public final class Recording implements Runnable {
    public enum Kind { CELLULAR, VOIP }

    private static final int FRAME = Sources.SAMPLE_RATE / 50; // 20 ms
    private static final int FIFO_MAX = Sources.SAMPLE_RATE / 2; // 500 ms

    public final Kind kind;
    public final File file;
    public final long startedAt = System.currentTimeMillis();

    private final Thread thread;
    private volatile boolean running = true;
    private volatile boolean failed;
    private long durationMs;

    private final AudioWriter.Format format;

    public Recording(Kind kind, File file, AudioWriter.Format format) {
        this.kind = kind;
        this.file = file;
        this.format = format;
        this.thread = new Thread(this, "recording");
    }

    public void start() {
        thread.start();
    }

    /** Stops and blocks until the file is finalized. Returns the recorded duration in ms. */
    public long stop() {
        running = false;
        try {
            thread.join(10_000);
        } catch (InterruptedException ignored) {
        }
        return failed ? 0 : durationMs;
    }

    @Override
    public void run() {
        Sources.Capture clock = null;
        Sources.Capture extra = null;
        AudioWriter writer = null;
        try {
            if (kind == Kind.CELLULAR) {
                clock = Sources.voiceCall();
                if (clock == null) {
                    clock = Sources.uplink();
                    extra = Sources.downlink();
                }
                if (clock == null) {
                    clock = Sources.mic();
                }
            } else {
                clock = Sources.mic();
                extra = Sources.voipDownlink();
            }
            if (clock == null && extra != null) {
                clock = extra;
                extra = null;
            }
            if (clock == null) {
                Log.i("no usable audio source, giving up on this call");
                failed = true;
                return;
            }

            writer = new AudioWriter(file.getPath(), format);
            loop(clock, extra, writer);
        } catch (Throwable t) {
            Log.e("recording failed", t);
            failed = true;
        } finally {
            if (clock != null) {
                clock.release();
            }
            if (extra != null) {
                extra.release();
            }
            if (writer != null) {
                durationMs = writer.durationMs();
                writer.close();
            }
        }
    }

    private void loop(Sources.Capture clock, Sources.Capture extra, AudioWriter writer) {
        short[] frame = new short[FRAME];
        short[] tmp = new short[FIFO_MAX];
        short[] fifo = new short[FIFO_MAX];
        int fifoLen = 0;
        Levels clockLv = new Levels(clock.name);
        Levels extraLv = extra != null ? new Levels(extra.name) : null;
        long nextReport = System.currentTimeMillis() + 5_000;

        while (running) {
            int n = readFully(clock.record, frame);
            if (n <= 0) {
                Log.i("clock source returned " + n + ", stopping");
                break;
            }
            clockLv.add(frame, n);

            if (extra != null) {
                int got = extra.record.read(tmp, 0, tmp.length, AudioRecord.READ_NON_BLOCKING);
                if (got > 0) {
                    extraLv.add(tmp, got);
                    if (fifoLen + got > FIFO_MAX) {
                        // The extra source is running ahead; drop its oldest audio to stay in sync.
                        int drop = fifoLen + got - FIFO_MAX;
                        System.arraycopy(fifo, drop, fifo, 0, fifoLen - drop);
                        fifoLen -= drop;
                    }
                    System.arraycopy(tmp, 0, fifo, fifoLen, got);
                    fifoLen += got;
                }
                int mix = Math.min(n, fifoLen);
                for (int i = 0; i < mix; i++) {
                    int s = frame[i] + fifo[i];
                    frame[i] = (short) (s > Short.MAX_VALUE ? Short.MAX_VALUE : Math.max(s, Short.MIN_VALUE));
                }
                System.arraycopy(fifo, mix, fifo, 0, fifoLen - mix);
                fifoLen -= mix;
            }

            writer.write(frame, n);

            long now = System.currentTimeMillis();
            if (now >= nextReport) {
                Log.d("levels " + clockLv.report() + (extraLv != null ? " " + extraLv.report() : ""));
                nextReport = now + 30_000;
            }
        }
    }

    private static int readFully(AudioRecord r, short[] buf) {
        int off = 0;
        while (off < buf.length) {
            int n = r.read(buf, off, buf.length - off, AudioRecord.READ_BLOCKING);
            if (n <= 0) {
                return off > 0 ? off : n;
            }
            off += n;
        }
        return off;
    }

    /** Peak tracker so the log shows whether each side actually carried audio. */
    private static final class Levels {
        private final String name;
        private int peak;
        private long samples;

        Levels(String name) {
            this.name = name;
        }

        void add(short[] b, int n) {
            for (int i = 0; i < n; i++) {
                int v = Math.abs((int) b[i]);
                if (v > peak) {
                    peak = v;
                }
            }
            samples += n;
        }

        String report() {
            String s = name + "[peak=" + peak + " sec=" + samples / Sources.SAMPLE_RATE + "]";
            peak = 0;
            return s;
        }
    }
}
