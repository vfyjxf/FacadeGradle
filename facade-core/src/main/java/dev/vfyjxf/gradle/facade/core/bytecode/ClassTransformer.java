package dev.vfyjxf.gradle.facade.core.bytecode;

public interface ClassTransformer {

    byte[] transform(String internalName, byte[] classBytes);
}
