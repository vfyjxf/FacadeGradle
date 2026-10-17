package dev.vfyjxf.gradle.facade.core.signature;

import dev.vfyjxf.gradle.facade.core.rules.ArrayTypeArg;
import dev.vfyjxf.gradle.facade.core.rules.ClassTypeArg;
import dev.vfyjxf.gradle.facade.core.rules.Suffix;
import dev.vfyjxf.gradle.facade.core.rules.TypeReference;
import dev.vfyjxf.gradle.facade.core.rules.TypeVariableArg;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ClassSignatureModelRoundTripTest {

    private static final List<String> FIXTURES = List.of(
            "<T:Ljava/lang/Object;>Ljava/lang/Object;Ljava/lang/Comparable<TT;>;",
            "<T::Ljava/lang/Comparable<TT;>;:Ljava/io/Serializable;>Ljava/lang/Object;",
            "<K:Ljava/lang/Object;V:TK;>Ljava/util/AbstractMap<TK;TV;>;Ljava/util/Map<TK;TV;>;",
            "<T:Ljava/lang/Object;>Ljava/lang/Object;Lcom/Outer<TT;>.Inner;",
            "Ljava/lang/Object;Ljava/lang/Comparable<[Ljava/lang/String;>;",
            "Ljava/lang/Object;Ljava/util/List<+[Ljava/lang/String;>;",
            "<T:[Ljava/lang/Object;>Ljava/lang/Object;",
            "Ljava/lang/Object;Ljava/io/Serializable;Ljava/lang/Cloneable;Ljava/util/function/Supplier<Ljava/lang/String;>;",
            "<T:Ljava/lang/Object;U::Ljava/io/Serializable;>Ljava/lang/Object;Ljava/util/List<TU;>;Ljava/util/Map<TT;TU;>;");

    @Test
    void writeIsVerbatimForCanonicalFixtures() {
        for (String fixture : FIXTURES) {
            assertEquals(fixture, ClassSignatures.write(ClassSignatures.parse(fixture)), fixture);
        }
    }

    @Test
    void parseWriteParseIsStructurallyStable() {
        for (String fixture : FIXTURES) {
            ClassSignatureModel once = ClassSignatures.parse(fixture);
            ClassSignatureModel twice = ClassSignatures.parse(ClassSignatures.write(once));
            assertEquals(once, twice, fixture);
        }
    }

    @Test
    void emptyClassBoundIsPreservedAndNull() {
        ClassSignatureModel model = ClassSignatures.parse(
                "<T::Ljava/lang/Comparable<TT;>;:Ljava/io/Serializable;>Ljava/lang/Object;");
        assertEquals(1, model.typeParameters().size());
        assertNull(model.typeParameters().get(0).classBound());
        assertEquals(2, model.typeParameters().get(0).interfaceBounds().size());
        assertEquals(new ClassTypeArg("java/lang/Comparable", List.of(new TypeVariableArg("T")), List.of()),
                model.typeParameters().get(0).interfaceBounds().get(0));
        assertEquals(new ClassTypeArg("java/io/Serializable", List.of(), List.of()),
                model.typeParameters().get(0).interfaceBounds().get(1));
        String written = ClassSignatures.write(model);
        assertTrue(written.contains("T::Ljava/lang/Comparable<TT;>;"), written);
    }

    @Test
    void nonEmptyClassBoundRoundTrips() {
        ClassSignatureModel model =
                ClassSignatures.parse("<T:Ljava/lang/Object;>Ljava/lang/Object;Ljava/lang/Comparable<TT;>;");
        assertEquals(new ClassTypeArg("java/lang/Object", List.of(), List.of()),
                model.typeParameters().get(0).classBound());
        assertTrue(model.typeParameters().get(0).interfaceBounds().isEmpty());
        assertEquals(new ClassTypeArg("java/lang/Comparable", List.of(new TypeVariableArg("T")), List.of()),
                model.superInterfaces().get(0));
    }

    @Test
    void classBoundMayBeATypeVariable() {
        ClassSignatureModel model =
                ClassSignatures.parse("<K:Ljava/lang/Object;V:TK;>Ljava/util/AbstractMap<TK;TV;>;Ljava/util/Map<TK;TV;>;");
        assertEquals(new TypeVariableArg("K"), model.typeParameters().get(1).classBound());
        assertEquals(Set.of("K", "V"), model.declaredTypeParameterNames());
    }

    @Test
    void dottedSuffixIsRepresentedAndRoundTrips() {
        ClassSignatureModel model = ClassSignatures.parse("<T:Ljava/lang/Object;>Ljava/lang/Object;Lcom/Outer<TT;>.Inner;");
        TypeReference superInterface = model.superInterfaces().get(0);
        assertEquals(new ClassTypeArg("com/Outer", List.of(new TypeVariableArg("T")),
                List.of(new Suffix("Inner", List.of()))), superInterface);
        assertTrue(ClassSignatures.write(model).contains("Lcom/Outer<TT;>.Inner;"));
    }

    @Test
    void arrayArgumentsCollapseDimensions() {
        ClassSignatureModel model = ClassSignatures.parse("Ljava/lang/Object;Ljava/lang/Comparable<[Ljava/lang/String;>;");
        ClassTypeArg comparable = (ClassTypeArg) model.superInterfaces().get(0);
        assertEquals(new ArrayTypeArg(1, new ClassTypeArg("java/lang/String", List.of(), List.of())),
                comparable.arguments().get(0));
        ClassSignatureModel deep = ClassSignatures.parse("Ljava/lang/Object;Ljava/util/function/Function<TT;[[Ljava/lang/String;>;");
        ClassTypeArg function = (ClassTypeArg) deep.superInterfaces().get(0);
        assertEquals(new ArrayTypeArg(2, new ClassTypeArg("java/lang/String", List.of(), List.of())),
                function.arguments().get(1));
    }

    @Test
    void wildcardWithArrayBoundRoundTrips() {
        ClassSignatureModel model = ClassSignatures.parse("Ljava/lang/Object;Ljava/util/List<+[Ljava/lang/String;>;");
        String written = ClassSignatures.write(model);
        assertEquals("Ljava/lang/Object;Ljava/util/List<+[Ljava/lang/String;>;", written);
    }

    @Test
    void multiInterfaceOrderPreserved() {
        ClassSignatureModel model = ClassSignatures.parse(
                "Ljava/lang/Object;Ljava/io/Serializable;Ljava/lang/Cloneable;Ljava/util/function/Supplier<Ljava/lang/String;>;");
        assertEquals(
                List.of(new ClassTypeArg("java/io/Serializable", List.of(), List.of()),
                        new ClassTypeArg("java/lang/Cloneable", List.of(), List.of()),
                        new ClassTypeArg("java/util/function/Supplier",
                                List.of(new ClassTypeArg("java/lang/String", List.of(), List.of())), List.of())),
                model.superInterfaces());
    }

    @Test
    void withAppendedInterfacesReturnsNewModelAndKeepsOrder() {
        ClassSignatureModel model = ClassSignatures.parse(
                "<T:Ljava/lang/Object;>Ljava/lang/Object;Ljava/lang/Comparable<TT;>;");
        ClassSignatureModel appended = model.withAppendedInterfaces(
                List.of(new ClassTypeArg("com/example/A", List.of(), List.of())));
        assertEquals(1, model.superInterfaces().size());
        assertEquals(2, appended.superInterfaces().size());
        assertEquals(new ClassTypeArg("java/lang/Comparable", List.of(new TypeVariableArg("T")), List.of()),
                appended.superInterfaces().get(0));
        assertEquals(new ClassTypeArg("com/example/A", List.of(), List.of()),
                appended.superInterfaces().get(1));
        assertEquals(model.typeParameters(), appended.typeParameters());
        assertEquals(model.superClass(), appended.superClass());
    }

    @Test
    void rejectsMalformedSignatures() {
        assertThrows(FacadeSignatureException.class, () -> ClassSignatures.parse(""));
        assertThrows(FacadeSignatureException.class, () -> ClassSignatures.parse("Lcom/X"));
        assertThrows(FacadeSignatureException.class, () -> ClassSignatures.parse("com/X"));
        assertThrows(FacadeSignatureException.class, () -> ClassSignatures.parse("Ljava/util/List<"));
        assertThrows(FacadeSignatureException.class, () -> ClassSignatures.parse("<T:Ljava/lang/Object;>"));
        assertThrows(FacadeSignatureException.class,
                () -> ClassSignatures.parse("<T::Ljava/lang/Comparable<TT;>;Ljava/io/Serializable;>Ljava/lang/Object;"));
    }
}
