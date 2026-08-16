package org.librewakeword;

/** Result of SODA's second-stage decision for one DSP candidate. */
final class SecondStageHotwordVerifier {
    static final class Result {
        final boolean accepted;
        final String detail;
        final SodaHotwordVerifier.DetectedAudio detectedAudio;

        Result(boolean accepted, String detail) {
            this(accepted, detail, null);
        }

        Result(boolean accepted, String detail,
                SodaHotwordVerifier.DetectedAudio detectedAudio) {
            this.accepted = accepted;
            this.detail = detail;
            this.detectedAudio = detectedAudio;
        }

        static Result reject(String detail) {
            return new Result(false, detail);
        }
    }

    private SecondStageHotwordVerifier() {
        throw new AssertionError();
    }
}
