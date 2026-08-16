package com.google.android.libraries.assistant.soda;

import android.content.Context;
import android.util.Log;

import java.nio.ByteBuffer;

public final class Soda implements AutoCloseable {
    public interface Callback {
        void onStart();
        void onEvent(byte[] event);
        void onStop(int reason);
        void onShutdown();
    }

    private static final String TAG = "LibreSoda";

    static {
        System.loadLibrary("soda_jni_no_terse");
    }

    private final Callback callback;
    private long sharedResources;
    private long nativeHandle;

    public Soda(Context context, Callback callback) {
        this.callback = callback;
        this.sharedResources = nativeCreateSharedResources(this);
        this.nativeHandle = nativeConstruct(sharedResources);
        Log.i(TAG, "created sharedResources=" + sharedResources + " handle=" + nativeHandle);
    }

    public byte[] init(byte[] coreConfig) {
        checkOpen();
        return nativeInit(nativeHandle, coreConfig);
    }

    public byte[] startCapture(byte[] clientConfig) {
        checkOpen();
        return nativeStartCapture(nativeHandle, clientConfig);
    }

    public boolean addAudio(ByteBuffer audio, int byteCount) {
        checkOpen();
        return nativeAddAudio(nativeHandle, audio, byteCount);
    }

    public boolean addTimestampedAudio(ByteBuffer audio, int byteCount,
            ByteBuffer timestamps, int timestampByteCount) {
        checkOpen();
        return nativeAddTimestampedAudio(nativeHandle, audio, byteCount,
                timestamps, timestampByteCount);
    }

    /**
     * Commits the text-dependent Voice Match enrollment event most recently emitted by SODA.
     * The request and response are serialized speech.soda task protos owned by the native API.
     */
    public byte[] enrollForVoiceMatch(byte[] request) {
        checkOpen();
        return nativeEnrollForVoiceMatch(nativeHandle,
                request == null ? new byte[0] : request);
    }

    /** Returns SODA's serialized local speaker-enrollment state. */
    public byte[] getSpeakerIdEnrollmentInfo() {
        checkOpen();
        return nativeGetSpeakerIdEnrollmentInfo(nativeHandle);
    }

    public void stopCapture() {
        if (nativeHandle != 0) {
            nativeStopCapture(nativeHandle);
        }
    }

    @Override
    public void close() {
        long handle = nativeHandle;
        nativeHandle = 0;
        if (handle != 0) {
            nativeDelete(handle);
        }
        long resources = sharedResources;
        if (resources != 0) {
            nativeDeleteSharedResourcesIfDiarizationProcessorAbsent(resources);
            sharedResources = 0;
        }
    }

    @SuppressWarnings("unused")
    private void handleStart() {
        Log.i(TAG, "handleStart");
        if (callback != null) {
            callback.onStart();
        }
    }

    @SuppressWarnings("unused")
    private void handleSodaEvent(byte[] event) {
        // SODA emits progress/streaming events for nearly every audio chunk. Logging every JNI
        // callback can produce hundreds of synchronous log writes inside Android's three-second
        // HDS decision window, and Voice Match enables additional larger streaming events.
        if (callback != null) {
            callback.onEvent(event);
        }
    }

    @SuppressWarnings("unused")
    private void handleStop(int reason) {
        Log.i(TAG, "handleStop reason=" + reason);
        if (callback != null) {
            callback.onStop(reason);
        }
    }

    @SuppressWarnings("unused")
    private void handleShutdown() {
        Log.i(TAG, "handleShutdown");
        if (callback != null) {
            callback.onShutdown();
        }
    }

    private void checkOpen() {
        if (nativeHandle == 0) {
            throw new IllegalStateException("SODA is closed");
        }
    }

    private static native long nativeCreateSharedResources(Object owner);
    private native long nativeConstruct(long sharedResources);
    private native byte[] nativeInit(long handle, byte[] coreConfig);
    private native byte[] nativeStartCapture(long handle, byte[] clientConfig);
    private native boolean nativeAddAudio(long handle, ByteBuffer audio, long byteCount);
    private native boolean nativeAddTimestampedAudio(long handle, ByteBuffer audio,
            long byteCount, ByteBuffer timestamps, long timestampByteCount);
    private native byte[] nativeEnrollForVoiceMatch(long handle, byte[] request);
    private native byte[] nativeGetSpeakerIdEnrollmentInfo(long handle);
    private native void nativeStopCapture(long handle);
    private native void nativeDelete(long handle);
    private native boolean nativeDeleteSharedResourcesIfDiarizationProcessorAbsent(
            long sharedResources);
}
