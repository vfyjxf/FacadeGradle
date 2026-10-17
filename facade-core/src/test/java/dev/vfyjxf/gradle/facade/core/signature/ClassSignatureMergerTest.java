package dev.vfyjxf.gradle.facade.core.signature;

import dev.vfyjxf.gradle.facade.core.rules.InjectedInterface;
import dev.vfyjxf.gradle.facade.core.rules.InterfaceInjectionRules;
import dev.vfyjxf.gradle.facade.core.rules.SourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClassSignatureMergerTest {

    private static final String TARGET = "com/example/Target";

    private static List<InjectedInterface> ifaces(String... expressions) {
        String joined = String.join("\", \"", expressions);
        InterfaceInjectionRules rules = InterfaceInjectionRules.parse(
                "{\"" + TARGET + "\": [\"" + joined + "\"]}", "merge-test");
        return rules.interfacesFor(TARGET);
    }

    @Test
    void appendsToExistingSignaturePreservingParamsAndSuperclass() {
        String existing = "<T:Ljava/lang/Object;>Ljava/lang/Object;Ljava/lang/Comparable<TT;>;";
        List<InjectedInterface> inject = ifaces("com/example/NewFace", "com/example/SupplierLike<T>");
        ClassSignatureMerger.Result result = ClassSignatureMerger.merge(
                existing, List.of(), "java/lang/Object", inject, TARGET);
        assertEquals(
                "<T:Ljava/lang/Object;>Ljava/lang/Object;Ljava/lang/Comparable<TT;>;Lcom/example/NewFace;Lcom/example/SupplierLike<TT;>;",
                result.signature());
        assertTrue(result.signatureChanged());
        assertEquals(inject, result.appended());
        assertTrue(result.skipped().isEmpty());
    }

    @Test
    void mergingTwiceIsIdempotent() {
        String existing = "<T:Ljava/lang/Object;>Ljava/lang/Object;Ljava/lang/Comparable<TT;>;";
        List<InjectedInterface> inject = ifaces("com/example/NewFace", "com/example/SupplierLike<T>");
        ClassSignatureMerger.Result first = ClassSignatureMerger.merge(
                existing, List.of(), "java/lang/Object", inject, TARGET);
        ClassSignatureMerger.Result second = ClassSignatureMerger.merge(
                first.signature(), List.of(), "java/lang/Object", inject, TARGET);
        assertEquals(first.signature(), second.signature());
        assertTrue(second.appended().isEmpty());
        assertEquals(inject, second.skipped());
        assertFalse(second.signatureChanged());
    }

    @Test
    void identicalInterfaceIsSkippedAndOriginalSignatureReturned() {
        String existing = "<T:Ljava/lang/Object;>Ljava/lang/Object;Lcom/example/NewFace<Ljava/lang/String;>;";
        List<InjectedInterface> inject = ifaces("com/example/NewFace<java.lang.String>");
        ClassSignatureMerger.Result result = ClassSignatureMerger.merge(
                existing, List.of(), "java/lang/Object", inject, TARGET);
        assertSame(existing, result.signature());
        assertFalse(result.signatureChanged());
        assertTrue(result.appended().isEmpty());
        assertEquals(inject, result.skipped());
    }

    @Test
    void conflictingArgumentsAreRejected() {
        String existing = "Ljava/lang/Object;Ljava/util/List<Ljava/lang/String;>;";
        List<InjectedInterface> inject = ifaces("java/util/List<java.lang.Integer>");
        FacadeSignatureException e = assertThrows(FacadeSignatureException.class,
                () -> ClassSignatureMerger.merge(existing, List.of(), "java/lang/Object", inject, TARGET));
        assertTrue(e.getMessage().contains(TARGET), e.getMessage());
        assertTrue(e.getMessage().contains("java/util/List"), e.getMessage());
        assertTrue(e.getMessage().contains("Ljava/util/List<Ljava/lang/String;>;"), e.getMessage());
        assertTrue(e.getMessage().contains("Ljava/util/List<Ljava/lang/Integer;>;"), e.getMessage());
        assertTrue(e.getMessage().contains("merge-test"), e.getMessage());
    }

    @Test
    void sameErasedNameInDottedSuffixFormConflictsWithDollarForm() {
        String existing = "Ljava/lang/Object;Lcom/Outer<TT;>.Inner;";
        List<InjectedInterface> inject = ifaces("com/Outer$Inner");
        assertThrows(FacadeSignatureException.class,
                () -> ClassSignatureMerger.merge(existing, List.of(), "java/lang/Object", inject, TARGET));
    }

    @Test
    void noSignatureWithRawOnlyReturnsNull() {
        List<InjectedInterface> inject = ifaces("com/example/A");
        ClassSignatureMerger.Result result = ClassSignatureMerger.merge(
                null, List.of("com/example/A"), "java/lang/Object", inject, TARGET);
        assertNull(result.signature());
        assertFalse(result.signatureChanged());
        assertTrue(result.appended().isEmpty());
        assertEquals(inject, result.skipped());
    }

    @Test
    void noSignatureSynthesizesWithExistingRawInterfacesAndSuperclass() {
        List<InjectedInterface> inject = ifaces("com/example/A", "com/example/Fn<java.lang.String>");
        ClassSignatureMerger.Result result = ClassSignatureMerger.merge(
                null, List.of("java/lang/Runnable", "com/example/A"), "my/Super", inject, TARGET);
        assertEquals(
                "Lmy/Super;Ljava/lang/Runnable;Lcom/example/A;Lcom/example/Fn<Ljava/lang/String;>;",
                result.signature());
        assertTrue(result.signatureChanged());
        assertEquals(List.of(inject.get(1)), result.appended());
        assertEquals(List.of(inject.get(0)), result.skipped());
    }

    @Test
    void noSignatureRejectsParameterizedRedeclarationOfErasedInterface() {
        List<InjectedInterface> inject = ifaces("java/util/List<java.lang.String>");
        FacadeSignatureException e = assertThrows(FacadeSignatureException.class,
                () -> ClassSignatureMerger.merge(null, List.of("java/util/List"), "java/lang/Object", inject, TARGET));
        assertTrue(e.getMessage().contains("no signature"), e.getMessage());
        assertTrue(e.getMessage().contains("merge-test"), e.getMessage());
    }

    @Test
    void noSignatureRejectsTypeVariableReferences() {
        List<InjectedInterface> inject = ifaces("com/example/Fn<T>");
        FacadeSignatureException e = assertThrows(FacadeSignatureException.class,
                () -> ClassSignatureMerger.merge(null, List.of(), "java/lang/Object", inject, TARGET));
        assertTrue(e.getMessage().contains("declares no type parameters"), e.getMessage());
        assertTrue(e.getMessage().contains("merge-test"), e.getMessage());
    }

    @Test
    void existingSignatureRejectsUndeclaredTypeVariable() {
        String existing = "<K:Ljava/lang/Object;>Ljava/lang/Object;";
        List<InjectedInterface> inject = ifaces("com/example/Fn<T>");
        FacadeSignatureException e = assertThrows(FacadeSignatureException.class,
                () -> ClassSignatureMerger.merge(existing, List.of(), "java/lang/Object", inject, TARGET));
        assertTrue(e.getMessage().contains("'T'"), e.getMessage());
        assertTrue(e.getMessage().contains("[K]"), e.getMessage());
        assertTrue(e.getMessage().contains("merge-test"), e.getMessage());
    }

    @Test
    void declaredTypeVariablesAreAcceptedInExistingSignatureCase() {
        String existing = "<T:Ljava/lang/Object;>Ljava/lang/Object;";
        List<InjectedInterface> inject = ifaces("com/example/SupplierLike<T>");
        ClassSignatureMerger.Result result = ClassSignatureMerger.merge(
                existing, List.of(), "java/lang/Object", inject, TARGET);
        assertEquals("<T:Ljava/lang/Object;>Ljava/lang/Object;Lcom/example/SupplierLike<TT;>;", result.signature());
    }

    @Test
    void duplicateEntriesWithinInjectListAreDeduplicated() {
        InjectedInterface first = new InjectedInterface(
                "com/example/A", "com/example/A", List.of(), new SourceLocation("merge-test", 1));
        InjectedInterface second = new InjectedInterface(
                "com/example/A", "com/example/A", List.of(), new SourceLocation("merge-test", 2));
        ClassSignatureMerger.Result result = ClassSignatureMerger.merge(
                null, List.of(), "java/lang/Object", List.of(first, second), TARGET);
        assertEquals("Ljava/lang/Object;Lcom/example/A;", result.signature());
        assertEquals(List.of(first), result.appended());
        assertEquals(List.of(second), result.skipped());
    }

    @Test
    void wildcardArgumentsSurviveMerge() {
        List<InjectedInterface> inject = ifaces("com/example/Fn<?, ? super java.lang.String>");
        ClassSignatureMerger.Result result = ClassSignatureMerger.merge(
                null, List.of(), "java/lang/Object", inject, TARGET);
        assertEquals("Ljava/lang/Object;Lcom/example/Fn<*-Ljava/lang/String;>;", result.signature());
    }

    @Test
    void deterministicOutputForIdenticalInputs() {
        String existing = "<T:Ljava/lang/Object;>Ljava/lang/Object;Ljava/lang/Comparable<TT;>;";
        List<InjectedInterface> inject = ifaces("com/example/A<T>", "com/example/B<java.lang.String>");
        String first = ClassSignatureMerger.merge(existing, List.of(), "java/lang/Object", inject, TARGET).signature();
        String second = ClassSignatureMerger.merge(existing, List.of(), "java/lang/Object", inject, TARGET).signature();
        assertEquals(first, second);
        assertEquals(
                "<T:Ljava/lang/Object;>Ljava/lang/Object;Ljava/lang/Comparable<TT;>;Lcom/example/A<TT;>;Lcom/example/B<Ljava/lang/String;>;",
                first);
    }

    @Test
    void emptyInjectListLeavesSignatureUntouched() {
        String existing = "<T:Ljava/lang/Object;>Ljava/lang/Object;";
        ClassSignatureMerger.Result result = ClassSignatureMerger.merge(
                existing, List.of(), "java/lang/Object", List.of(), TARGET);
        assertSame(existing, result.signature());
        assertFalse(result.signatureChanged());
        assertTrue(result.appended().isEmpty());
        assertTrue(result.skipped().isEmpty());
    }
}
