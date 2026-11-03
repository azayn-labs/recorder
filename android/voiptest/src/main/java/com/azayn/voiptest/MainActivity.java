package com.azayn.voiptest;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;

/** Behaves like a VoIP app in a call; logs its mic peak every 500 ms (0 = silenced). */
public class MainActivity extends Activity {
    private volatile boolean running = true;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        AudioManager am = getSystemService(AudioManager.class);
        // Like WhatsApp on Samsung (calls go through Telecom), this app doesn't own the call mode
        // unless asked; the test sets the mode from another process.
        if (getIntent().getBooleanExtra("ownMode", false)) am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        if (getIntent().getBooleanExtra("telecom", false)) {
            // Place a self-managed call; Telecom then sets MODE_IN_COMMUNICATION itself.
            android.telecom.TelecomManager tm = getSystemService(android.telecom.TelecomManager.class);
            android.telecom.PhoneAccountHandle h = new android.telecom.PhoneAccountHandle(
                    new android.content.ComponentName(this, CallService.class), "voiptest");
            tm.registerPhoneAccount(android.telecom.PhoneAccount.builder(h, "VoIP test")
                    .setCapabilities(android.telecom.PhoneAccount.CAPABILITY_SELF_MANAGED).build());
            Bundle extras = new Bundle();
            extras.putParcelable(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, h);
            tm.placeCall(android.net.Uri.fromParts("sip", "test", null), extras);
        }
        new Thread(() -> {
            int rate = 48000;
            AudioRecord rec = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .build();
            // The "other person": voice-communication playback, as WhatsApp does.
            AudioTrack out = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .build();
            rec.startRecording();
            out.play();
            short[] buf = new short[rate / 2];
            short[] silence = new short[rate / 2];
            while (running) {
                int n = rec.read(buf, 0, buf.length);
                int peak = 0;
                long sum = 0;
                for (int i = 0; i < n; i++) {
                    int v = Math.abs((int) buf[i]);
                    peak = Math.max(peak, v);
                    sum += v;
                }
                Log.i("VoipTest", "peak=" + peak + " mean=" + (n > 0 ? sum / n : -1));
                out.write(silence, 0, silence.length, AudioTrack.WRITE_NON_BLOCKING);
            }
            rec.release();
            out.release();
            if (getIntent().getBooleanExtra("ownMode", false)) am.setMode(AudioManager.MODE_NORMAL);
        }).start();
    }

    @Override
    protected void onDestroy() {
        running = false;
        if (CallService.current != null) CallService.current.onDisconnect();
        super.onDestroy();
    }
}
