package dev.vfyjxf.gradle.facade.core.signature;

import dev.vfyjxf.gradle.facade.core.rules.ArrayTypeArg;
import dev.vfyjxf.gradle.facade.core.rules.ClassTypeArg;
import dev.vfyjxf.gradle.facade.core.rules.FacadeRuleException;
import dev.vfyjxf.gradle.facade.core.rules.InjectedInterface;
import dev.vfyjxf.gradle.facade.core.rules.Suffix;
import dev.vfyjxf.gradle.facade.core.rules.TypeArg;
import dev.vfyjxf.gradle.facade.core.rules.TypeReference;
import dev.vfyjxf.gradle.facade.core.rules.TypeVariableArg;
import dev.vfyjxf.gradle.facade.core.rules.WildcardTypeArg;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.signature.SignatureWriter;

import java.util.List;
import java.util.Objects;

public final class ClassSignatures {

    private ClassSignatures() {}

    public static String toSignatureFragment(InjectedInterface iface) {
        Objects.requireNonNull(iface, "iface");
        rejectArrays(iface, iface.arguments());
        SignatureWriter writer = new SignatureWriter();
        writer.visitClassType(iface.internalName());
        writeArguments(writer, iface.arguments());
        writer.visitEnd();
        return writer.toString();
    }

    public static ClassSignatureModel parse(String classSignature) {
        return SignatureParsing.parse(classSignature);
    }

    public static String write(ClassSignatureModel model) {
        Objects.requireNonNull(model, "model");
        SignatureWriter writer = new SignatureWriter();
        for (TypeParameter parameter : model.typeParameters()) {
            writer.visitFormalTypeParameter(parameter.name());
            SignatureVisitor classBound = writer.visitClassBound();
            if (parameter.classBound() != null) {
                writeReference(classBound, parameter.classBound());
            }
            for (TypeReference bound : parameter.interfaceBounds()) {
                writeReference(writer.visitInterfaceBound(), bound);
            }
        }
        writeReference(writer.visitSuperclass(), model.superClass());
        for (TypeReference superInterface : model.superInterfaces()) {
            writeReference(writer.visitInterface(), superInterface);
        }
        return writer.toString();
    }

    static String toFragment(TypeReference reference) {
        SignatureWriter writer = new SignatureWriter();
        writeReference(writer, reference);
        return writer.toString();
    }

    static void writeReference(SignatureVisitor visitor, TypeReference reference) {
        switch (reference) {
            case ClassTypeArg classType -> {
                visitor.visitClassType(classType.internalName());
                writeArguments(visitor, classType.arguments());
                for (Suffix suffix : classType.suffixes()) {
                    visitor.visitInnerClassType(suffix.simpleName());
                    writeArguments(visitor, suffix.arguments());
                }
                visitor.visitEnd();
            }
            case TypeVariableArg typeVariable -> visitor.visitTypeVariable(typeVariable.name());
            case ArrayTypeArg array -> {
                SignatureVisitor inner = visitor;
                for (int i = 0; i < array.dimensions(); i++) {
                    inner = inner.visitArrayType();
                }
                writeReference(inner, (TypeReference) array.component());
            }
        }
    }

    private static void writeArguments(SignatureVisitor visitor, List<TypeArg> arguments) {
        for (TypeArg argument : arguments) {
            if (argument instanceof WildcardTypeArg wildcard) {
                switch (wildcard.kind()) {
                    case UNBOUNDED -> visitor.visitTypeArgument();
                    case EXTENDS -> writeReference(visitor.visitTypeArgument(SignatureVisitor.EXTENDS), asReference(wildcard.bound()));
                    case SUPER -> writeReference(visitor.visitTypeArgument(SignatureVisitor.SUPER), asReference(wildcard.bound()));
                }
            } else {
                writeReference(visitor.visitTypeArgument(SignatureVisitor.INSTANCEOF), asReference(argument));
            }
        }
    }

    private static TypeReference asReference(TypeArg argument) {
        if (argument instanceof WildcardTypeArg) {
            throw new FacadeSignatureException("wildcard at reference position: " + argument);
        }
        return (TypeReference) argument;
    }

    private static void rejectArrays(InjectedInterface iface, List<TypeArg> arguments) {
        for (TypeArg argument : arguments) {
            rejectArrays(iface, argument);
        }
    }

    private static void rejectArrays(InjectedInterface iface, TypeArg argument) {
        switch (argument) {
            case ArrayTypeArg array -> throw new FacadeRuleException(
                    "array types are not supported for interface injection: '" + iface.rawText() + "'", iface.source());
            case ClassTypeArg classType -> {
                rejectArrays(iface, classType.arguments());
                for (Suffix suffix : classType.suffixes()) {
                    rejectArrays(iface, suffix.arguments());
                }
            }
            case WildcardTypeArg wildcard -> {
                if (wildcard.bound() != null) {
                    rejectArrays(iface, wildcard.bound());
                }
            }
            case TypeVariableArg ignored -> {
            }
        }
    }
}
