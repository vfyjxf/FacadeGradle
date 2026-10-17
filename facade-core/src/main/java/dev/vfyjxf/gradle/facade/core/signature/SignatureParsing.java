package dev.vfyjxf.gradle.facade.core.signature;

import dev.vfyjxf.gradle.facade.core.rules.ArrayTypeArg;
import dev.vfyjxf.gradle.facade.core.rules.ClassTypeArg;
import dev.vfyjxf.gradle.facade.core.rules.Suffix;
import dev.vfyjxf.gradle.facade.core.rules.TypeArg;
import dev.vfyjxf.gradle.facade.core.rules.TypeReference;
import dev.vfyjxf.gradle.facade.core.rules.TypeVariableArg;
import dev.vfyjxf.gradle.facade.core.rules.WildcardKind;
import dev.vfyjxf.gradle.facade.core.rules.WildcardTypeArg;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

final class SignatureParsing {

    private SignatureParsing() {}

    static ClassSignatureModel parse(String signature) {
        Objects.requireNonNull(signature, "signature");
        ModelBuilder builder = new ModelBuilder();
        try {
            new SignatureReader(signature).accept(builder);
            return builder.build(signature);
        } catch (FacadeSignatureException e) {
            throw new FacadeSignatureException("malformed class signature '" + signature + "': " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new FacadeSignatureException("malformed class signature '" + signature + "': " + e, e);
        }
    }

    private static final class ModelBuilder extends SignatureVisitor {

        private final List<TypeParameter> typeParameters = new ArrayList<>();
        private final List<TypeReference> pendingInterfaceBounds = new ArrayList<>();
        private final List<TypeReference> superInterfaces = new ArrayList<>();
        private String pendingName;
        private TypeArg pendingClassBound;
        private TypeArg superClass;

        ModelBuilder() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visitFormalTypeParameter(String name) {
            finishParameter();
            pendingName = name;
            pendingClassBound = null;
            pendingInterfaceBounds.clear();
        }

        @Override
        public SignatureVisitor visitClassBound() {
            return new ReferenceBuilder(SignatureVisitor.INSTANCEOF, arg -> pendingClassBound = arg);
        }

        @Override
        public SignatureVisitor visitInterfaceBound() {
            return new ReferenceBuilder(SignatureVisitor.INSTANCEOF, arg -> pendingInterfaceBounds.add(reference(arg)));
        }

        @Override
        public SignatureVisitor visitSuperclass() {
            finishParameter();
            return new ReferenceBuilder(SignatureVisitor.INSTANCEOF, arg -> superClass = arg);
        }

        @Override
        public SignatureVisitor visitInterface() {
            return new ReferenceBuilder(SignatureVisitor.INSTANCEOF, arg -> superInterfaces.add(reference(arg)));
        }

        ClassSignatureModel build(String signature) {
            finishParameter();
            if (superClass == null) {
                throw new FacadeSignatureException("missing superclass reference in '" + signature + "'");
            }
            return new ClassSignatureModel(typeParameters, reference(superClass), superInterfaces);
        }

        private void finishParameter() {
            if (pendingName == null) {
                return;
            }
            typeParameters.add(new TypeParameter(pendingName, referenceOrNull(pendingClassBound), List.copyOf(pendingInterfaceBounds)));
            pendingName = null;
        }

        private static TypeReference reference(TypeArg arg) {
            if (arg instanceof WildcardTypeArg) {
                throw new FacadeSignatureException("wildcard at reference position");
            }
            return (TypeReference) arg;
        }

        private static TypeReference referenceOrNull(TypeArg arg) {
            return arg == null ? null : reference(arg);
        }
    }

    /**
     * Sealing rules driven by how SignatureReader reports content: class-type frames seal
     * on visitEnd, type-variable frames seal immediately, array shells seal when their
     * component is delivered. A frame that never receives content stays undelivered,
     * which is how an empty class bound stays distinguishable from a null one.
     */
    private static final class ReferenceBuilder extends SignatureVisitor {

        private final char variance;
        private final Consumer<TypeArg> sink;
        private final List<TypeArg> rootArguments = new ArrayList<>();
        private final List<String> suffixNames = new ArrayList<>();
        private final List<List<TypeArg>> suffixArguments = new ArrayList<>();
        private List<TypeArg> currentArguments = rootArguments;
        private String internalName;
        private int arrayDepth;
        private boolean sealed;

        ReferenceBuilder(char variance, Consumer<TypeArg> sink) {
            super(Opcodes.ASM9);
            this.variance = variance;
            this.sink = sink;
        }

        @Override
        public void visitClassType(String name) {
            internalName = name;
        }

        @Override
        public void visitInnerClassType(String name) {
            suffixNames.add(name);
            List<TypeArg> arguments = new ArrayList<>();
            suffixArguments.add(arguments);
            currentArguments = arguments;
        }

        @Override
        public void visitTypeArgument() {
            currentArguments.add(new WildcardTypeArg(WildcardKind.UNBOUNDED, null));
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            return new ReferenceBuilder(wildcard, currentArguments::add);
        }

        @Override
        public void visitTypeVariable(String name) {
            deliver(new TypeVariableArg(name));
        }

        @Override
        public SignatureVisitor visitArrayType() {
            arrayDepth++;
            return new ReferenceBuilder(SignatureVisitor.INSTANCEOF, this::deliver);
        }

        @Override
        public void visitEnd() {
            if (internalName == null || arrayDepth > 0) {
                return;
            }
            List<Suffix> suffixes = new ArrayList<>(suffixNames.size());
            for (int i = 0; i < suffixNames.size(); i++) {
                suffixes.add(new Suffix(suffixNames.get(i), List.copyOf(suffixArguments.get(i))));
            }
            deliver(new ClassTypeArg(internalName, List.copyOf(rootArguments), suffixes));
        }

        private void deliver(TypeArg content) {
            if (sealed) {
                return;
            }
            sealed = true;
            TypeArg delivered = content;
            if (arrayDepth > 0) {
                delivered = content instanceof ArrayTypeArg inner
                        ? new ArrayTypeArg(arrayDepth + inner.dimensions(), inner.component())
                        : new ArrayTypeArg(arrayDepth, content);
            }
            if (variance == SignatureVisitor.EXTENDS) {
                delivered = new WildcardTypeArg(WildcardKind.EXTENDS, delivered);
            } else if (variance == SignatureVisitor.SUPER) {
                delivered = new WildcardTypeArg(WildcardKind.SUPER, delivered);
            }
            sink.accept(delivered);
        }
    }
}
