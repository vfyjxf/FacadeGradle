package dev.vfyjxf.gradle.facade.core.signature;

import dev.vfyjxf.gradle.facade.core.rules.ArrayTypeArg;
import dev.vfyjxf.gradle.facade.core.rules.ClassTypeArg;
import dev.vfyjxf.gradle.facade.core.rules.InjectedInterface;
import dev.vfyjxf.gradle.facade.core.rules.Suffix;
import dev.vfyjxf.gradle.facade.core.rules.TypeArg;
import dev.vfyjxf.gradle.facade.core.rules.TypeReference;
import dev.vfyjxf.gradle.facade.core.rules.TypeVariableArg;
import dev.vfyjxf.gradle.facade.core.rules.WildcardTypeArg;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ClassSignatureMerger {

    private ClassSignatureMerger() {}

    /**
     * {@code signature} is null exactly when the class needs no signature attribute
     * (nothing was appended and none existed before).
     */
    public record Result(
            String signature,
            List<InjectedInterface> appended,
            List<InjectedInterface> skipped,
            boolean signatureChanged) {

        public Result {
            appended = List.copyOf(appended);
            skipped = List.copyOf(skipped);
        }
    }

    public static Result merge(
            String existingSignature,
            List<String> existingErasedSuperInterfaces,
            String erasedSuperClass,
            List<InjectedInterface> toInject,
            String targetInternalName) {
        Objects.requireNonNull(existingErasedSuperInterfaces, "existingErasedSuperInterfaces");
        Objects.requireNonNull(toInject, "toInject");
        Objects.requireNonNull(targetInternalName, "targetInternalName");
        List<InjectedInterface> injected = List.copyOf(toInject);
        List<Pending> pending = new ArrayList<>(injected.size());
        for (InjectedInterface iface : injected) {
            String fragment = ClassSignatures.toSignatureFragment(iface);
            TypeReference reference = ClassSignatures.parse(fragment).superClass();
            pending.add(new Pending(iface, reference, erasedName(reference)));
        }
        return existingSignature != null
                ? mergeWithSignature(existingSignature, pending, targetInternalName)
                : mergeWithoutSignature(existingErasedSuperInterfaces, erasedSuperClass, pending, targetInternalName);
    }

    private static Result mergeWithSignature(String existingSignature, List<Pending> pending, String target) {
        ClassSignatureModel model = ClassSignatures.parse(existingSignature);
        List<Pending> appended = new ArrayList<>();
        List<TypeReference> appendedReferences = new ArrayList<>();
        List<InjectedInterface> skipped = new ArrayList<>();
        for (Pending entry : pending) {
            TypeReference existing = findByErasedName(model.superInterfaces(), entry.erasedName());
            if (existing == null) {
                existing = findByErasedName(appendedReferences, entry.erasedName());
            }
            if (existing != null) {
                if (existing.equals(entry.reference())) {
                    skipped.add(entry.iface());
                } else {
                    throw conflict(target, entry, existing);
                }
            } else {
                appendedReferences.add(entry.reference());
                appended.add(entry);
            }
        }
        Set<String> declared = model.declaredTypeParameterNames();
        for (Pending entry : appended) {
            checkDeclaredTypeVariables((TypeArg) entry.reference(), entry.iface(), declared, target);
        }
        if (appended.isEmpty()) {
            return new Result(existingSignature, List.of(), List.copyOf(skipped), false);
        }
        String merged = ClassSignatures.write(model.withAppendedInterfaces(appendedReferences));
        List<InjectedInterface> appendedIfaces = new ArrayList<>(appended.size());
        for (Pending entry : appended) {
            appendedIfaces.add(entry.iface());
        }
        return new Result(merged, List.copyOf(appendedIfaces), List.copyOf(skipped), true);
    }

    private static Result mergeWithoutSignature(
            List<String> existingErasedSuperInterfaces, String erasedSuperClass, List<Pending> pending, String target) {
        List<Pending> appended = new ArrayList<>();
        List<TypeReference> appendedReferences = new ArrayList<>();
        List<InjectedInterface> skipped = new ArrayList<>();
        for (Pending entry : pending) {
            boolean alreadyDeclared =
                    existingErasedSuperInterfaces.contains(entry.erasedName())
                            || findByErasedName(appendedReferences, entry.erasedName()) != null;
            if (alreadyDeclared) {
                if (isRaw(entry.reference())) {
                    skipped.add(entry.iface());
                } else {
                    throw new FacadeSignatureException(
                            "cannot inject parameterized interface '" + entry.erasedName() + "' into '" + target
                                    + "': the class has no signature but already declares the interface in raw form"
                                    + " (rule at " + entry.iface().source() + ")");
                }
            } else {
                appendedReferences.add(entry.reference());
                appended.add(entry);
            }
        }
        for (Pending entry : appended) {
            rejectAnyTypeVariable((TypeArg) entry.reference(), entry.iface(), target);
        }
        if (appended.isEmpty()) {
            return new Result(null, List.of(), List.copyOf(skipped), false);
        }
        if (erasedSuperClass == null) {
            throw new FacadeSignatureException(
                    "cannot synthesize a signature for '" + target + "': the erased superclass is unknown");
        }
        List<TypeReference> superInterfaces = new ArrayList<>();
        for (String name : existingErasedSuperInterfaces) {
            superInterfaces.add(new ClassTypeArg(name, List.of(), List.of()));
        }
        superInterfaces.addAll(appendedReferences);
        ClassSignatureModel synthesized =
                new ClassSignatureModel(List.of(), new ClassTypeArg(erasedSuperClass, List.of(), List.of()), superInterfaces);
        List<InjectedInterface> appendedIfaces = new ArrayList<>(appended.size());
        for (Pending entry : appended) {
            appendedIfaces.add(entry.iface());
        }
        return new Result(ClassSignatures.write(synthesized), List.copyOf(appendedIfaces), List.copyOf(skipped), true);
    }

    private record Pending(InjectedInterface iface, TypeReference reference, String erasedName) {}

    private static boolean isRaw(TypeReference reference) {
        return reference instanceof ClassTypeArg classType
                && classType.arguments().isEmpty()
                && classType.suffixes().isEmpty();
    }

    private static TypeReference findByErasedName(List<TypeReference> references, String erasedName) {
        for (TypeReference reference : references) {
            if (erasedName(reference).equals(erasedName)) {
                return reference;
            }
        }
        return null;
    }

    private static String erasedName(TypeReference reference) {
        if (reference instanceof ClassTypeArg classType) {
            if (classType.suffixes().isEmpty()) {
                return classType.internalName();
            }
            StringBuilder sb = new StringBuilder(classType.internalName());
            for (Suffix suffix : classType.suffixes()) {
                sb.append('$').append(suffix.simpleName());
            }
            return sb.toString();
        }
        throw new FacadeSignatureException(
                "unexpected reference type at superinterface position: " + ClassSignatures.toFragment(reference));
    }

    private static FacadeSignatureException conflict(String target, Pending entry, TypeReference existing) {
        return new FacadeSignatureException(
                "cannot inject interface '" + entry.erasedName() + "' into '" + target
                        + "': already declared with different type arguments: existing "
                        + ClassSignatures.toFragment(existing) + ", rule " + ClassSignatures.toFragment(entry.reference())
                        + " (rule at " + entry.iface().source() + ")");
    }

    private static void rejectAnyTypeVariable(TypeArg arg, InjectedInterface iface, String target) {
        switch (arg) {
            case TypeVariableArg typeVariable -> throw new FacadeSignatureException(
                    "cannot reference type variable '" + typeVariable.name() + "' when target '" + target
                            + "' declares no type parameters (rule at " + iface.source() + ")");
            case ClassTypeArg classType -> {
                for (TypeArg child : classType.arguments()) {
                    rejectAnyTypeVariable(child, iface, target);
                }
                for (Suffix suffix : classType.suffixes()) {
                    for (TypeArg child : suffix.arguments()) {
                        rejectAnyTypeVariable(child, iface, target);
                    }
                }
            }
            case WildcardTypeArg wildcard -> {
                if (wildcard.bound() != null) {
                    rejectAnyTypeVariable(wildcard.bound(), iface, target);
                }
            }
            case ArrayTypeArg array -> rejectAnyTypeVariable(array.component(), iface, target);
        }
    }

    private static void checkDeclaredTypeVariables(
            TypeArg arg, InjectedInterface iface, Set<String> declared, String target) {
        switch (arg) {
            case TypeVariableArg typeVariable -> {
                if (!declared.contains(typeVariable.name())) {
                    throw new FacadeSignatureException(
                            "interface '" + iface.internalName() + "' for target '" + target
                                    + "' references type variable '" + typeVariable.name()
                                    + "' not declared by the target (declares "
                                    + (declared.isEmpty() ? "no type parameters" : declared) + "); rule at "
                                    + iface.source());
                }
            }
            case ClassTypeArg classType -> {
                for (TypeArg child : classType.arguments()) {
                    checkDeclaredTypeVariables(child, iface, declared, target);
                }
                for (Suffix suffix : classType.suffixes()) {
                    for (TypeArg child : suffix.arguments()) {
                        checkDeclaredTypeVariables(child, iface, declared, target);
                    }
                }
            }
            case WildcardTypeArg wildcard -> {
                if (wildcard.bound() != null) {
                    checkDeclaredTypeVariables(wildcard.bound(), iface, declared, target);
                }
            }
            case ArrayTypeArg array -> checkDeclaredTypeVariables(array.component(), iface, declared, target);
        }
    }
}
