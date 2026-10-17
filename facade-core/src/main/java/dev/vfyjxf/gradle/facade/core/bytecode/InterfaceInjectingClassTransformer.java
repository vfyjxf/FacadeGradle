package dev.vfyjxf.gradle.facade.core.bytecode;

import dev.vfyjxf.gradle.facade.core.rules.InjectedInterface;
import dev.vfyjxf.gradle.facade.core.rules.InterfaceInjectionRules;
import dev.vfyjxf.gradle.facade.core.signature.ClassSignatureMerger;
import dev.vfyjxf.gradle.facade.core.signature.FacadeSignatureException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Thread-safe, stateless {@link ClassTransformer} that appends the interfaces declared for a class by
 * {@link InterfaceInjectionRules} to its interfaces table and merges the generic signature accordingly;
 * all other class file content passes through untouched.
 * Limitation: for rewritten (matched) classes only, non-standard attributes ASM does not model
 * (e.g. CharacterRangeTable) are dropped.
 */
public final class InterfaceInjectingClassTransformer implements ClassTransformer {

    private static final String MODULE_INFO = "module-info";

    private final InterfaceInjectionRules rules;

    private InterfaceInjectingClassTransformer(InterfaceInjectionRules rules) {
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    public static InterfaceInjectingClassTransformer of(InterfaceInjectionRules rules) {
        return new InterfaceInjectingClassTransformer(rules);
    }

    @Override
    public byte[] transform(String internalName, byte[] classBytes) {
        Objects.requireNonNull(internalName, "internalName");
        Objects.requireNonNull(classBytes, "classBytes");
        if (MODULE_INFO.equals(internalName) || !rules.targetsClass(internalName)) {
            return classBytes;
        }
        Header header = readHeader(internalName, classBytes);
        ClassSignatureMerger.Result result = merge(header);
        if (result.appended().isEmpty() && !result.signatureChanged()) {
            return classBytes;
        }
        return rewrite(internalName, classBytes, result);
    }

    private Header readHeader(String internalName, byte[] classBytes) {
        ClassReader reader = newClassReader(internalName, classBytes);
        HeaderCapture capture = new HeaderCapture();
        try {
            reader.accept(capture, ClassReader.SKIP_CODE);
        } catch (RuntimeException e) {
            throw new FacadeTransformException("failed to read class '" + internalName + "'", e);
        }
        if (capture.name == null || !capture.name.equals(internalName)) {
            throw new FacadeTransformException(
                    "internal name mismatch: caller requested '" + internalName
                            + "' but the class bytes declare '" + capture.name + "'");
        }
        String[] interfaces = capture.interfaces == null ? new String[0] : capture.interfaces;
        return new Header(capture.name, capture.signature, capture.superName, interfaces);
    }

    private ClassSignatureMerger.Result merge(Header header) {
        try {
            return ClassSignatureMerger.merge(
                    header.signature(),
                    List.of(header.interfaces()),
                    header.superName(),
                    rules.interfacesFor(header.name()),
                    header.name());
        } catch (FacadeSignatureException e) {
            throw new FacadeTransformException(e.getMessage(), e);
        }
    }

    private byte[] rewrite(String internalName, byte[] classBytes, ClassSignatureMerger.Result result) {
        ClassReader reader = newClassReader(internalName, classBytes);
        // flag 0: method code, frames and maxs pass through verbatim
        ClassWriter writer = new ClassWriter(0);
        try {
            reader.accept(new RewritingVisitor(writer, result), 0);
            return writer.toByteArray();
        } catch (RuntimeException e) {
            throw new FacadeTransformException("failed to rewrite class '" + internalName + "'", e);
        }
    }

    private static ClassReader newClassReader(String internalName, byte[] classBytes) {
        try {
            return new ClassReader(classBytes);
        } catch (RuntimeException e) {
            throw new FacadeTransformException("cannot read class '" + internalName + "': " + e, e);
        }
    }

    private record Header(String name, String signature, String superName, String[] interfaces) {}

    private static final class HeaderCapture extends ClassVisitor {

        String name;
        String signature;
        String superName;
        String[] interfaces;

        HeaderCapture() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(
                int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.name = name;
            this.signature = signature;
            this.superName = superName;
            this.interfaces = interfaces;
        }
    }

    private static final class RewritingVisitor extends ClassVisitor {

        private final ClassSignatureMerger.Result result;

        RewritingVisitor(ClassVisitor delegate, ClassSignatureMerger.Result result) {
            super(Opcodes.ASM9, delegate);
            this.result = result;
        }

        @Override
        public void visit(
                int version, int access, String name, String signature, String superName, String[] interfaces) {
            String[] newInterfaces = interfaces == null ? new String[0] : interfaces;
            if (!result.appended().isEmpty()) {
                LinkedHashSet<String> merged = new LinkedHashSet<>(Arrays.asList(newInterfaces));
                for (InjectedInterface iface : result.appended()) {
                    merged.add(iface.internalName());
                }
                newInterfaces = merged.toArray(new String[0]);
            }
            super.cv.visit(version, access, name, result.signature(), superName, newInterfaces);
        }
    }
}
