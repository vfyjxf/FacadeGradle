package dev.vfyjxf.gradle.facade.core.bytecode;

public class FacadeTransformException extends RuntimeException {

    public FacadeTransformException(String message) {
        super(message);
    }

    public FacadeTransformException(String message, Throwable cause) {
        super(message, cause);
    }
}
