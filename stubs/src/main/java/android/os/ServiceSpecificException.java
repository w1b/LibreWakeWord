package android.os;

// Compile-time-only stub matching the real hidden class; not packaged into the APK.
public class ServiceSpecificException extends RuntimeException {
    public final int errorCode;
    public ServiceSpecificException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
