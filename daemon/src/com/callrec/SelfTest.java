package com.callrec;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;

/** Opens every capture source briefly and reports whether it works and its peak level. */
public final class SelfTest {
    private SelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Env.init();
        audioInfo();
        test(Sources.mic());
        test(Sources.voipDownlink());
        Log.i("now playing a test tone as USAGE_VOICE_COMMUNICATION (like a VoIP call):");
        AudioTrack tone = tone();
        tone.play();
        test(Sources.mic());
        test(Sources.voipDownlink());
        tone.stop();
        tone.release();
        test(Sources.voiceCall());
        test(Sources.uplink());
        test(Sources.downlink());
        System.exit(0);
    }

    private static AudioTrack tone() {
        int rate = Sources.SAMPLE_RATE;
        short[] pcm = new short[rate * 6];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (12000 * Math.sin(2 * Math.PI * 1000 * i / rate));
        }
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.length * 2)
                .build();
        t.write(pcm, 0, pcm.length);
        return t;
    }

    private static void audioInfo() {
        Log.i("audio mode=" + CallInfo.audioManager().getMode()
                + " voipPackage=" + CallInfo.voipPackage(CallInfo.audioManager()));
    }

    private static void test(Sources.Capture c) {
        if (c == null) {
            return;
        }
        short[] buf = new short[Sources.SAMPLE_RATE / 10];
        int total = 0;
        int peak = 0;
        long end = System.currentTimeMillis() + 1500;
        while (System.currentTimeMillis() < end) {
            int n = c.record.read(buf, 0, buf.length, AudioRecord.READ_NON_BLOCKING);
            if (n > 0) {
                total += n;
                for (int i = 0; i < n; i++) {
                    peak = Math.max(peak, Math.abs((int) buf[i]));
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
        }
        Log.i("  " + c.name + ": " + total + " samples in 1.5 s, peak " + peak);
        c.release();
    }
}
