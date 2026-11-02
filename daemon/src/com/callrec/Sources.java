package com.callrec;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.lang.reflect.Method;

/** Factory for the AudioRecord instances a call recording needs. */
public final class Sources {
    public static final int SAMPLE_RATE = 48000;
    public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    public static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private Sources() {
    }

    public static AudioFormat format() {
        return new AudioFormat.Builder()
                .setEncoding(ENCODING)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build();
    }

    /** A started AudioRecord plus whatever must be torn down with it. */
    public static final class Capture {
        public final String name;
        public final AudioRecord record;
        private final Object policy;

        Capture(String name, AudioRecord record, Object policy) {
            this.name = name;
            this.record = record;
            this.policy = policy;
        }

        public void release() {
            try {
                record.stop();
            } catch (Throwable ignored) {
            }
            record.release();
            if (policy != null) {
                unregisterPolicy(policy);
            }
        }
    }

    /** Direct capture from a MediaRecorder.AudioSource (VOICE_CALL, MIC, ...). */
    public static Capture direct(String name, int source) {
        try {
            AudioRecord.Builder b = new AudioRecord.Builder()
                    .setContext(Env.context())
                    .setAudioSource(source)
                    .setAudioFormat(format());
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
            if (min > 0) {
                b.setBufferSizeInBytes(8 * min);
            }
            AudioRecord r = b.build();
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                Log.i("source " + name + ": not initialized");
                return null;
            }
            r.startRecording();
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                r.release();
                Log.i("source " + name + ": failed to start");
                return null;
            }
            Log.i("source " + name + ": recording");
            return new Capture(name, r, null);
        } catch (Throwable t) {
            Log.e("source " + name + " failed", t);
            return null;
        }
    }

    public static Capture voiceCall() {
        return direct("voice_call", MediaRecorder.AudioSource.VOICE_CALL);
    }

    public static Capture uplink() {
        return direct("uplink", MediaRecorder.AudioSource.VOICE_UPLINK);
    }

    public static Capture downlink() {
        return direct("downlink", MediaRecorder.AudioSource.VOICE_DOWNLINK);
    }

    public static Capture mic() {
        Capture c = direct("mic_voice_comm", MediaRecorder.AudioSource.VOICE_COMMUNICATION);
        if (c == null) {
            c = direct("mic", MediaRecorder.AudioSource.MIC);
        }
        return c;
    }

    /**
     * Captures what the device plays with USAGE_VOICE_COMMUNICATION (the remote party of a
     * VoIP call) through a loopback audio policy, while still rendering it to the speaker.
     * Requires CAPTURE_AUDIO_OUTPUT + CAPTURE_VOICE_COMMUNICATION_OUTPUT, which shell has.
     */
    public static Capture voipDownlink() {
        Object policy = null;
        try {
            Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
            Class<?> ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
            Object ruleBuilder = ruleBuilderClass.getConstructor().newInstance();
            ruleBuilderClass.getMethod("setTargetMixRole", int.class)
                    .invoke(ruleBuilder, ruleClass.getField("MIX_ROLE_PLAYERS").getInt(null));
            int matchUsage = ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null);
            Method addMixRule = ruleBuilderClass.getMethod("addMixRule", int.class, Object.class);
            addMixRule.invoke(ruleBuilder, matchUsage,
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build());
            ruleBuilderClass.getMethod("voiceCommunicationCaptureAllowed", boolean.class).invoke(ruleBuilder, true);
            Object rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder);

            Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
            Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
            Object mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule);
            mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, format());
            int flags = mixClass.getField("ROUTE_FLAG_LOOP_BACK_RENDER").getInt(null);
            mixBuilderClass.getMethod("setRouteFlags", int.class).invoke(mixBuilder, flags);
            Object mix = mixBuilderClass.getMethod("build").invoke(mixBuilder);

            Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
            Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
            Object policyBuilder = policyBuilderClass.getConstructor(Context.class).newInstance(Env.context());
            policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix);
            policy = policyBuilderClass.getMethod("build").invoke(policyBuilder);

            Method register = AudioManager.class.getDeclaredMethod("registerAudioPolicyStatic", policyClass);
            register.setAccessible(true);
            int res = (int) register.invoke(null, policy);
            if (res != 0) {
                Log.i("source voip_downlink: registerAudioPolicy returned " + res);
                return null;
            }
            AudioRecord r = (AudioRecord) policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix);
            r.startRecording();
            Log.i("source voip_downlink: recording");
            return new Capture("voip_downlink", r, policy);
        } catch (Throwable t) {
            Log.e("source voip_downlink failed", t);
            if (policy != null) {
                unregisterPolicy(policy);
            }
            return null;
        }
    }

    private static void unregisterPolicy(Object policy) {
        try {
            Method m = AudioManager.class.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", policy.getClass());
            m.setAccessible(true);
            m.invoke(null, policy);
            return;
        } catch (Throwable ignored) {
        }
        try {
            AudioManager am = (AudioManager) Env.context().getSystemService(Context.AUDIO_SERVICE);
            Method m = AudioManager.class.getMethod("unregisterAudioPolicy", policy.getClass());
            m.invoke(am, policy);
        } catch (Throwable t) {
            Log.e("unregisterAudioPolicy failed", t);
        }
    }
}
