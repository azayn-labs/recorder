package com.callrec;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Encodes mono 16-bit PCM to AAC-LC in an .m4a container. */
public final class AacWriter {
    private static final int BITRATE = 64000;
    private static final long TIMEOUT_US = 10_000;

    private final MediaCodec codec;
    private final MediaMuxer muxer;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private int track = -1;
    private boolean muxerStarted;
    private long samplesIn;

    public AacWriter(String path) throws IOException {
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Sources.SAMPLE_RATE, 1);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        muxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    public long durationMs() {
        return samplesIn * 1000 / Sources.SAMPLE_RATE;
    }

    /** Writes {@code count} samples from {@code pcm}. */
    public void write(short[] pcm, int count) {
        int off = 0;
        while (off < count) {
            int idx = codec.dequeueInputBuffer(TIMEOUT_US);
            if (idx < 0) {
                drain(false);
                continue;
            }
            ByteBuffer in = codec.getInputBuffer(idx);
            in.clear();
            in.order(ByteOrder.LITTLE_ENDIAN);
            int n = Math.min(count - off, in.remaining() / 2);
            for (int i = 0; i < n; i++) {
                in.putShort(pcm[off + i]);
            }
            long pts = samplesIn * 1_000_000L / Sources.SAMPLE_RATE;
            codec.queueInputBuffer(idx, 0, n * 2, pts, 0);
            samplesIn += n;
            off += n;
            drain(false);
        }
    }

    public void close() {
        try {
            int idx;
            while ((idx = codec.dequeueInputBuffer(TIMEOUT_US)) < 0) {
                drain(false);
            }
            long pts = samplesIn * 1_000_000L / Sources.SAMPLE_RATE;
            codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drain(true);
        } catch (Throwable t) {
            Log.e("encoder flush failed", t);
        }
        try {
            codec.stop();
        } catch (Throwable ignored) {
        }
        codec.release();
        try {
            if (muxerStarted) {
                muxer.stop();
            }
        } catch (Throwable t) {
            Log.e("muxer stop failed", t);
        }
        muxer.release();
    }

    private void drain(boolean untilEos) {
        while (true) {
            int idx = codec.dequeueOutputBuffer(info, TIMEOUT_US);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos) {
                    return;
                }
                continue;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                muxerStarted = true;
                continue;
            }
            if (idx < 0) {
                continue;
            }
            ByteBuffer out = codec.getOutputBuffer(idx);
            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (!config && info.size > 0 && muxerStarted) {
                out.position(info.offset);
                out.limit(info.offset + info.size);
                muxer.writeSampleData(track, out, info);
            }
            codec.releaseOutputBuffer(idx, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                return;
            }
        }
    }
}
