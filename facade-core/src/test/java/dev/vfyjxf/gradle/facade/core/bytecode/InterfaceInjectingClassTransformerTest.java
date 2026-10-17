package dev.vfyjxf.gradle.facade.core.bytecode;

import dev.vfyjxf.gradle.facade.core.fixtures.ClassInspect;
import dev.vfyjxf.gradle.facade.core.fixtures.FixtureCompiler;
import dev.vfyjxf.gradle.facade.core.fixtures.FixtureSources;
import dev.vfyjxf.gradle.facade.core.rules.FacadeRuleException;
import dev.vfyjxf.gradle.facade.core.rules.InterfaceInjectionRules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class InterfaceInjectingClassTransformerTest {

    private static final String SECTION_32_SIGNATURE =
            "<T:Ljava/lang/Object;>Ljava/lang/Object;Ljava/util/function/Supplier<TT;>;";

    @TempDir
    static Path fixtureDir;

    @BeforeAll
    static void compileFixtures() {
        FixtureCompiler.compile(fixtureDir, FixtureSources.core());
    }

    private static byte[] classBytes(String internalName) {
        try {
            return FixtureCompiler.classBytes(fixtureDir, internalName);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InterfaceInjectionRules rules(String json) {
        return InterfaceInjectionRules.parse(json, "bytecode-test");
    }

    private static byte[] transform(String internalName, String json, byte[] bytes) {
        return InterfaceInjectingClassTransformer.of(rules(json)).transform(internalName, bytes);
    }

    @Test
    @DisplayName("1. plain class + plain interface: interfaces table contains it")
    void plainClassGetsPlainInterface() {
        byte[] original = classBytes("plain/Target");
        assertNull(ClassInspect.info(original).signature);
        byte[] patched = transform("plain/Target", "{\"plain/Target\": \"java.io.Serializable\"}", original);
        assertNotSame(original, patched);
        ClassInspect.verify(patched);
        ClassInspect.Info info = ClassInspect.info(patched);
        assertEquals("plain/Target", info.name);
        assertArrayEquals(new String[]{"java/io/Serializable"}, info.interfaces);
        assertEquals("Ljava/lang/Object;Ljava/io/Serializable;", info.signature);
        assertEquals(2, info.methods);
        assertEquals(1, info.fields);
    }

    @Test
    @DisplayName("2. acceptance: Target<T> + Supplier<T> gets exact merged signature")
    void acceptanceGenericSupplier() {
        byte[] patched = transform("gen/Target", "{\"gen/Target\": \"java.util.function.Supplier<T>\"}",
                classBytes("gen/Target"));
        ClassInspect.verify(patched);
        ClassInspect.Info info = ClassInspect.info(patched);
        assertArrayEquals(new String[]{"java/util/function/Supplier"}, info.interfaces);
        assertEquals(SECTION_32_SIGNATURE, info.signature);
    }

    @Test
    @DisplayName("3. multiple interfaces in one pass")
    void multipleInterfacesInOnePass() {
        byte[] patched = transform("plain/Target",
                "{\"plain/Target\": [\"java.io.Serializable\", \"java.lang.Cloneable\"]}", classBytes("plain/Target"));
        ClassInspect.verify(patched);
        ClassInspect.Info info = ClassInspect.info(patched);
        assertArrayEquals(new String[]{"java/io/Serializable", "java/lang/Cloneable"}, info.interfaces);
        assertEquals("Ljava/lang/Object;Ljava/io/Serializable;Ljava/lang/Cloneable;", info.signature);
    }

    @Test
    @DisplayName("4. already-present identical interfaces: re-transform returns the same array instance")
    void secondRunIsIdempotent() {
        InterfaceInjectingClassTransformer plain =
                InterfaceInjectingClassTransformer.of(rules("{\"plain/Target\": \"java.io.Serializable\"}"));
        byte[] plainOriginal = classBytes("plain/Target");
        byte[] plainPatched = plain.transform("plain/Target", plainOriginal);
        assertNotSame(plainOriginal, plainPatched);
        assertSame(plainPatched, plain.transform("plain/Target", plainPatched));

        InterfaceInjectingClassTransformer generic =
                InterfaceInjectingClassTransformer.of(rules("{\"gen/Target\": \"java.util.function.Supplier<T>\"}"));
        byte[] genOriginal = classBytes("gen/Target");
        byte[] genPatched = generic.transform("gen/Target", genOriginal);
        assertNotSame(genOriginal, genPatched);
        assertSame(genPatched, generic.transform("gen/Target", genPatched));
        ClassInspect.Info info = ClassInspect.info(genPatched);
        assertArrayEquals(new String[]{"java/util/function/Supplier"}, info.interfaces);
        assertEquals(SECTION_32_SIGNATURE, info.signature);
    }

    @Test
    @DisplayName("5. conflicting type arguments: FacadeTransformException with both shapes")
    void conflictingArgumentsRejected() {
        FacadeTransformException e = assertThrows(FacadeTransformException.class,
                () -> transform("conflict/ConflictTarget",
                        "{\"conflict/ConflictTarget\": \"java.util.function.Supplier<java.lang.String>\"}",
                        classBytes("conflict/ConflictTarget")));
        assertTrue(e.getMessage().contains("Ljava/util/function/Supplier<Ljava/lang/Integer;>;"), e.getMessage());
        assertTrue(e.getMessage().contains("Ljava/util/function/Supplier<Ljava/lang/String;>;"), e.getMessage());
    }

    @Test
    @DisplayName("6. type variable into non-generic class rejected")
    void typeVariableIntoNonGenericClassRejected() {
        FacadeTransformException e = assertThrows(FacadeTransformException.class,
                () -> transform("plain/Target", "{\"plain/Target\": \"java.util.function.Supplier<T>\"}",
                        classBytes("plain/Target")));
        assertTrue(e.getMessage().contains("T"), e.getMessage());
    }

    @Test
    @DisplayName("7. undeclared type variable name rejected")
    void wrongTypeVariableNameRejected() {
        FacadeTransformException e = assertThrows(FacadeTransformException.class,
                () -> transform("gen/Target", "{\"gen/Target\": \"java.util.function.Supplier<U>\"}",
                        classBytes("gen/Target")));
        assertTrue(e.getMessage().contains("U"), e.getMessage());
    }

    @Test
    @DisplayName("8. existing generics (superclass + interface) preserved verbatim")
    void existingGenericsPreserved() {
        byte[] patched = transform("gen/ExtendsExisting",
                "{\"gen/ExtendsExisting\": \"java.io.Serializable\"}", classBytes("gen/ExtendsExisting"));
        ClassInspect.verify(patched);
        ClassInspect.Info info = ClassInspect.info(patched);
        assertEquals("java/util/ArrayList", info.superName);
        assertArrayEquals(new String[]{"java/lang/Comparable", "java/io/Serializable"}, info.interfaces);
        assertEquals(
                "Ljava/util/ArrayList<Ljava/lang/String;>;Ljava/lang/Comparable<Lgen/ExtendsExisting;>;"
                        + "Ljava/io/Serializable;",
                info.signature);
    }

    @Test
    @DisplayName("9. nested generics + wildcards")
    void nestedGenericsAndWildcards() {
        byte[] patched = transform("gen/Target",
                "{\"gen/Target\": ["
                        + "\"java.util.function.Function<java.util.List<T>, java.util.Map$Entry<java.lang.String, T>>\","
                        + "\"java.util.function.Consumer<? extends java.lang.Number>\"]}",
                classBytes("gen/Target"));
        ClassInspect.verify(patched);
        ClassInspect.Info info = ClassInspect.info(patched);
        assertArrayEquals(
                new String[]{"java/util/function/Function", "java/util/function/Consumer"}, info.interfaces);
        assertEquals(
                "<T:Ljava/lang/Object;>Ljava/lang/Object;"
                        + "Ljava/util/function/Function<Ljava/util/List<TT;>;Ljava/util/Map$Entry<Ljava/lang/String;TT;>;>;"
                        + "Ljava/util/function/Consumer<+Ljava/lang/Number;>;",
                info.signature);
    }

    @Test
    @DisplayName("10. inner class target (Outer$Inner)")
    void innerClassTarget() {
        byte[] original = classBytes("nested/Outer$Inner");
        byte[] outer = classBytes("nested/Outer");
        byte[] patched = transform("nested/Outer$Inner", "{\"nested/Outer$Inner\": \"java.io.Serializable\"}",
                original);
        ClassInspect.verify(patched);
        assertArrayEquals(new String[]{"java/io/Serializable"}, ClassInspect.info(patched).interfaces);
        InterfaceInjectingClassTransformer transformer =
                InterfaceInjectingClassTransformer.of(rules("{\"nested/Outer$Inner\": \"java.io.Serializable\"}"));
        assertSame(outer, transformer.transform("nested/Outer", outer));
    }

    @Test
    @DisplayName("11. record / enum / sealed: components, flags and PermittedSubclasses preserved")
    void recordEnumSealedTargets() {
        byte[] record = transform("rec/Point", "{\"rec/Point\": \"java.io.Serializable\"}", classBytes("rec/Point"));
        ClassInspect.verify(record);
        ClassInspect.Info recordInfo = ClassInspect.info(record);
        assertEquals("java/lang/Record", recordInfo.superName);
        assertEquals(2, recordInfo.recordComponents);
        assertArrayEquals(new String[]{"java/io/Serializable"}, recordInfo.interfaces);

        byte[] enumBytes = transform("enm/Color", "{\"enm/Color\": \"java.io.Serializable\"}", classBytes("enm/Color"));
        ClassInspect.verify(enumBytes);
        ClassInspect.Info enumInfo = ClassInspect.info(enumBytes);
        assertEquals("java/lang/Enum", enumInfo.superName);
        assertArrayEquals(new String[]{"java/io/Serializable"}, enumInfo.interfaces);

        byte[] sealedShape = transform("sealed/Shape", "{\"sealed/Shape\": \"java.io.Serializable\"}",
                classBytes("sealed/Shape"));
        ClassInspect.verify(sealedShape);
        ClassInspect.Info shapeInfo = ClassInspect.info(sealedShape);
        assertEquals(List.of("sealed/Circle"), shapeInfo.permittedSubclasses);
        assertArrayEquals(new String[]{"java/io/Serializable"}, shapeInfo.interfaces);

        byte[] sealedCircle = transform("sealed/Circle", "{\"sealed/Circle\": \"java.io.Serializable\"}",
                classBytes("sealed/Circle"));
        ClassInspect.verify(sealedCircle);
        assertArrayEquals(new String[]{"sealed/Shape", "java/io/Serializable"},
                ClassInspect.info(sealedCircle).interfaces);
    }

    @Test
    @DisplayName("12. untargeted class: same array instance, everything byte-identical")
    void untargetedClassUntouched() {
        byte[] original = classBytes("anno/Marked");
        byte[] result = transform("anno/Marked", "{\"plain/Target\": \"java.io.Serializable\"}", original);
        assertSame(original, result);

        byte[] frames = classBytes("frames/Frames");
        InterfaceInjectingClassTransformer transformer =
                InterfaceInjectingClassTransformer.of(rules("{\"plain/Target\": \"java.io.Serializable\"}"));
        assertSame(frames, transformer.transform("frames/Frames", frames));
        ClassInspect.verify(frames);
    }

    @Test
    @DisplayName("13. runtime proof: patched class satisfies and dispatches through Supplier")
    void runtimeSupplierDispatch() throws Exception {
        InterfaceInjectingClassTransformer transformer = InterfaceInjectingClassTransformer.of(rules(
                "{\"run/GenTarget\": \"java.util.function.Supplier<T>\","
                        + " \"run/SupplierTarget\": \"java.util.function.Supplier\"}"));

        byte[] genOriginal = classBytes("run/GenTarget");
        byte[] genPatched = transformer.transform("run/GenTarget", genOriginal);
        ClassInspect.verify(genPatched);
        ClassLoader parent = InterfaceInjectingClassTransformerTest.class.getClassLoader();
        Class<?> pre = new BytesLoader(parent).define("run/GenTarget", genOriginal);
        Class<?> post = new BytesLoader(parent).define("run/GenTarget", genPatched);
        assertFalse(Supplier.class.isAssignableFrom(pre));
        assertTrue(Supplier.class.isAssignableFrom(post));
        Object genericInstance = post.getDeclaredConstructor(Object.class).newInstance("gen-value");
        assertEquals("gen-value", ((Supplier<?>) genericInstance).get());

        byte[] rawOriginal = classBytes("run/SupplierTarget");
        byte[] rawPatched = transformer.transform("run/SupplierTarget", rawOriginal);
        ClassInspect.verify(rawPatched);
        Class<?> rawPre = new BytesLoader(parent).define("run/SupplierTarget", rawOriginal);
        Class<?> rawPost = new BytesLoader(parent).define("run/SupplierTarget", rawPatched);
        assertFalse(Supplier.class.isAssignableFrom(rawPre));
        assertTrue(Supplier.class.isAssignableFrom(rawPost));
        Object rawInstance = rawPost.getDeclaredConstructor().newInstance();
        assertEquals("supplied", ((Supplier<?>) rawInstance).get());
    }

    @Test
    @DisplayName("14. internal name mismatch rejected")
    void internalNameMismatchRejected() {
        InterfaceInjectingClassTransformer transformer =
                InterfaceInjectingClassTransformer.of(rules("{\"plain/Target\": \"java.io.Serializable\"}"));
        byte[] otherBytes = classBytes("gen/Target");
        FacadeTransformException e = assertThrows(FacadeTransformException.class,
                () -> transformer.transform("plain/Target", otherBytes));
        assertTrue(e.getMessage().contains("plain/Target"), e.getMessage());
        assertTrue(e.getMessage().contains("gen/Target"), e.getMessage());

        FacadeTransformException garbage = assertThrows(FacadeTransformException.class,
                () -> transformer.transform("plain/Target", new byte[]{1, 2, 3, 4}));
        assertNotNull(garbage.getMessage());
    }

    @Test
    @DisplayName("15. module-info cannot be targeted (parser) and is never transformed (defensive)")
    void moduleInfoNeverTransformed() {
        assertThrows(FacadeRuleException.class,
                () -> rules("{\"module-info\": \"java.io.Serializable\"}"));
        InterfaceInjectingClassTransformer transformer =
                InterfaceInjectingClassTransformer.of(rules("{\"plain/Target\": \"java.io.Serializable\"}"));
        byte[] bytes = classBytes("plain/Target");
        assertSame(bytes, transformer.transform("module-info", bytes));
    }

    private static final class BytesLoader extends ClassLoader {

        BytesLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String internalName, byte[] bytes) {
            String binaryName = internalName.replace('/', '.');
            Class<?> defined = defineClass(binaryName, bytes, 0, bytes.length);
            resolveClass(defined);
            return defined;
        }
    }
}
