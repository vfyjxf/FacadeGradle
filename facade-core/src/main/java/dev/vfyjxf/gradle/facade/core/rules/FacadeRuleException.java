package dev.vfyjxf.gradle.facade.core.rules;

public class FacadeRuleException extends RuntimeException {

    private final SourceLocation location;

    public FacadeRuleException(String message) {
        this(message, null, null);
    }

    public FacadeRuleException(String message, SourceLocation location) {
        this(message, null, location);
    }

    public FacadeRuleException(String message, Throwable cause, SourceLocation location) {
        super(message, cause);
        this.location = location;
    }

    public SourceLocation location() {
        return location;
    }
}
