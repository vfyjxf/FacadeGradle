package dev.vfyjxf.gradle.facade.core.signature;

public class FacadeSignatureException extends RuntimeException {

    public FacadeSignatureException(String message) {
        super(message);
    }

    public FacadeSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
