package dev.vfyjxf.gradle.facade.core.signature;

import dev.vfyjxf.gradle.facade.core.rules.ArrayTypeArg;
import dev.vfyjxf.gradle.facade.core.rules.ClassTypeArg;
import dev.vfyjxf.gradle.facade.core.rules.FacadeRuleException;
import dev.vfyjxf.gradle.facade.core.rules.InjectedInterface;
import dev.vfyjxf.gradle.facade.core.rules.InterfaceInjectionRules;
import dev.vfyjxf.gradle.facade.core.rules.SourceLocation;
import dev.vfyjxf.gradle.facade.core.rules.WildcardKind;
import dev.vfyjxf.gradle.facade.core.rules.WildcardTypeArg;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClassSignaturesFragmentTest {

    private static String fragment(String expression) {
        InterfaceInjectionRules rules = InterfaceInjectionRules.parse(
                "{\"com/example/Host\": \"" + expression + "\"}", "fragment-test");
        return ClassSignatures.toSignatureFragment(rules.interfacesFor("com/example/Host").get(0));
    }

    @Test
    void plainInterface() {
        assertEquals("Lcom/example/ISomething;", fragment("com/example/ISomething"));
    }

    @Test
    void dottedBaseNameBecomesSlashed() {
        assertEquals("Lcom/example/Dotted;", fragment("com.example.Dotted"));
    }

    @Test
    void typeVariableArgument() {
        assertEquals("Ljava/util/function/Supplier<TT;>;", fragment("java/util/function/Supplier<T>"));
    }

    @Test
    void extendsWildcard() {
        assertEquals("Lcom/example/W<+Ljava/util/Number;>;", fragment("com/example/W<? extends java.util.Number>"));
        assertEquals("Lcom/example/W<+Ljava/lang/Number;>;", fragment("com/example/W<? extends java.lang.Number>"));
    }

    @Test
    void superWildcard() {
        assertEquals("Lcom/example/W<-Ljava/lang/String;>;", fragment("com/example/W<? super java.lang.String>"));
    }

    @Test
    void unboundedWildcard() {
        assertEquals("Lcom/example/W<*>;", fragment("com/example/W<?>"));
    }

    @Test
    void mixedWildcardAndConcreteArguments() {
        assertEquals("Lcom/example/M<*-Ljava/lang/String;Ljava/lang/Object;>;",
                fragment("com/example/M<?, ? super java.lang.String, java.lang.Object>"));
    }

    @Test
    void nestedParameterization() {
        assertEquals("Lcom/example/F2<Ljava/util/List<TT;>;Ljava/util/Map$Entry<Ljava/lang/String;TT;>;>;",
                fragment("com/example/F2<java.util.List<T>, java.util.Map$Entry<java.lang.String, T>>"));
    }

    @Test
    void wildcardBoundWithNestedGenerics() {
        assertEquals("Lcom/example/X<+Ljava/util/List<Ljava/lang/String;>;>;",
                fragment("com/example/X<? extends java.util.List<java.lang.String>>"));
    }

    @Test
    void innerClassesUseDollarForm() {
        assertEquals("Lcom/example/I1$Inner;", fragment("com/example/I1$Inner"));
        assertEquals("Lcom/example/I1$Inner$SubInner;", fragment("com/example/I1$Inner$SubInner"));
    }

    @Test
    void rejectsArrayTypeArguments() {
        InjectedInterface withArray = new InjectedInterface(
                "com/example/I<java.lang.String[]>",
                "com/example/I",
                List.of(new ArrayTypeArg(1, new ClassTypeArg("java/lang/String", List.of(), List.of()))),
                new SourceLocation("fragment-test", -1));
        FacadeRuleException e = assertThrows(FacadeRuleException.class,
                () -> ClassSignatures.toSignatureFragment(withArray));
        assertTrue(e.getMessage().contains("array types are not supported"), e.getMessage());
    }

    @Test
    void rejectsArrayTypeInsideWildcardBound() {
        InjectedInterface withArrayBound = new InjectedInterface(
                "com/example/I<? extends java.lang.String[]>",
                "com/example/I",
                List.of(new WildcardTypeArg(WildcardKind.EXTENDS,
                        new ArrayTypeArg(1, new ClassTypeArg("java/lang/String", List.of(), List.of())))),
                new SourceLocation("fragment-test", -1));
        assertThrows(FacadeRuleException.class, () -> ClassSignatures.toSignatureFragment(withArrayBound));
    }

    @Test
    void fragmentsParseBackIntoEquivalentModels() {
        String fragment = fragment("com/example/F2<java.util.List<T>, java.util.Map$Entry<java.lang.String, T>>");
        ClassSignatureModel model = ClassSignatures.parse(fragment);
        assertEquals(new ClassTypeArg("com/example/F2", List.of(
                        new ClassTypeArg("java/util/List", List.of(new dev.vfyjxf.gradle.facade.core.rules.TypeVariableArg("T")), List.of()),
                        new ClassTypeArg("java/util/Map$Entry", List.of(
                                new ClassTypeArg("java/lang/String", List.of(), List.of()),
                                new dev.vfyjxf.gradle.facade.core.rules.TypeVariableArg("T")), List.of())),
                List.of()), model.superClass());
        assertTrue(model.typeParameters().isEmpty());
        assertTrue(model.superInterfaces().isEmpty());
    }
}
