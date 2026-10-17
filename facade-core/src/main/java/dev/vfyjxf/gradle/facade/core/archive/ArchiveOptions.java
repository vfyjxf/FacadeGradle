package dev.vfyjxf.gradle.facade.core.archive;

import java.util.Objects;

public final class ArchiveOptions {

    private static final long DEFAULT_MAX_UNCOMPRESSED_BYTES = 2L * 1024L * 1024L * 1024L;

    private final SignedJarPolicy signedJarPolicy;
    private final long maxUncompressedBytes;

    private ArchiveOptions(SignedJarPolicy signedJarPolicy, long maxUncompressedBytes) {
        if (maxUncompressedBytes <= 0) {
            throw new IllegalArgumentException("maxUncompressedBytes must be positive: " + maxUncompressedBytes);
        }
        this.signedJarPolicy = Objects.requireNonNull(signedJarPolicy, "signedJarPolicy");
        this.maxUncompressedBytes = maxUncompressedBytes;
    }

    public static ArchiveOptions defaults() {
        return new ArchiveOptions(SignedJarPolicy.FAIL, DEFAULT_MAX_UNCOMPRESSED_BYTES);
    }

    public SignedJarPolicy signedJarPolicy() {
        return signedJarPolicy;
    }

    public long maxUncompressedBytes() {
        return maxUncompressedBytes;
    }

    public ArchiveOptions withSignedJarPolicy(SignedJarPolicy signedJarPolicy) {
        return new ArchiveOptions(signedJarPolicy, maxUncompressedBytes);
    }

    public ArchiveOptions withMaxUncompressedBytes(long bytes) {
        return new ArchiveOptions(signedJarPolicy, bytes);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ArchiveOptions other)) {
            return false;
        }
        return signedJarPolicy == other.signedJarPolicy && maxUncompressedBytes == other.maxUncompressedBytes;
    }

    @Override
    public int hashCode() {
        return Objects.hash(signedJarPolicy, maxUncompressedBytes);
    }

    @Override
    public String toString() {
        return "ArchiveOptions[signedJarPolicy=" + signedJarPolicy
                + ", maxUncompressedBytes=" + maxUncompressedBytes + "]";
    }
}
