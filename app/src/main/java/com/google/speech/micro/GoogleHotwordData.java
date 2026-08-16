package com.google.speech.micro;

public class GoogleHotwordData {
    private long nativeHotwordData;

    public GoogleHotwordData(byte[] data, int sampleRate, int channelCount, int flags) {
        this(data, sampleRate, channelCount, flags, false);
    }

    public GoogleHotwordData(byte[] data, int sampleRate, int channelCount, int flags,
            boolean extraFlag) {
        nativeHotwordData = nativeNew(data, sampleRate, channelCount, flags, extraFlag);
    }

    public String getHotwordModelId() {
        return nativeGetHotwordModelId(nativeHotwordData);
    }

    public int idealBufferBytes() {
        return nativeIdealBufferBytes(nativeHotwordData);
    }

    public boolean isSpeakerVerificationEnabled() {
        return nativeIsSpeakerVerificationEnabled(nativeHotwordData);
    }

    public boolean isSpeakerModelCompatible(byte[] speakerModel) {
        return nativeIsSpeakerModelCompatible(nativeHotwordData, speakerModel);
    }

    public int maxNumImpostorAttemptsForVoiceUnlock() {
        return nativeMaxNumImpostorAttemptsForVoiceUnlock(nativeHotwordData);
    }

    public synchronized void close() {
        if (nativeHotwordData != 0L) {
            nativeClose(nativeHotwordData);
            nativeHotwordData = 0L;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    private static native long nativeNew(byte[] data, int sampleRate, int channelCount, int flags,
            boolean extraFlag);
    private static native void nativeClose(long nativeHotwordData);
    private static native String nativeGetHotwordModelId(long nativeHotwordData);
    private static native int nativeIdealBufferBytes(long nativeHotwordData);
    private static native boolean nativeIsSpeakerVerificationEnabled(long nativeHotwordData);
    private static native boolean nativeIsSpeakerModelCompatible(long nativeHotwordData,
            byte[] speakerModel);
    private static native int nativeMaxNumImpostorAttemptsForVoiceUnlock(long nativeHotwordData);
}
