package dev.vfyjxf.gradle.facade.core.rules;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InterfaceInjectionRulesParseTest {

    private static final String DESC = "test-rules";

    private static InjectedInterface singleInterface(String value) {
        InterfaceInjectionRules rules = InterfaceInjectionRules.parse("{\"com/example/T\": " + value + "}", DESC);
        List<InjectedInterface> interfaces = rules.interfacesFor("com/example/T");
        assertEquals(1, interfaces.size());
        return interfaces.get(0);
    }

    @Test
    void plainStringValue() {
        InjectedInterface iface = singleInterface("\"com/example/I\"");
        assertEquals("com/example/I", iface.internalName());
        assertTrue(iface.arguments().isEmpty());
        assertEquals("com/example/I", iface.rawText());
        assertEquals(DESC, iface.source().file());
        assertTrue(iface.source().line() >= 1);
    }

    @Test
    void arrayValueKeepsStableOrder() {
        InterfaceInjectionRules rules =
                InterfaceInjectionRules.parse("{\"t/T\": [\"a/B\", \"c/D\"]}", DESC);
        List<InjectedInterface> interfaces = rules.interfacesFor("t/T");
        assertEquals(2, interfaces.size());
        assertEquals("a/B", interfaces.get(0).internalName());
        assertEquals("c/D", interfaces.get(1).internalName());
    }

    @Test
    void emptyObjectIsEmpty() {
        InterfaceInjectionRules rules = InterfaceInjectionRules.parse("{}", DESC);
        assertTrue(rules.isEmpty());
        assertFalse(rules.targetsClass("a/B"));
        assertTrue(rules.interfacesFor("a/B").isEmpty());
        assertTrue(rules.asMap().isEmpty());
    }

    @Test
    void genericTypeVariableArguments() {
        InjectedInterface iface = singleInterface("\"com/InjectedGeneric<T, MyTarget>\"");
        assertEquals("com/InjectedGeneric", iface.internalName());
        assertEquals(List.of(new TypeVariableArg("T"), new TypeVariableArg("MyTarget")), iface.arguments());
    }

    @Test
    void innerDollarNames() {
        InterfaceInjectionRules rules =
                InterfaceInjectionRules.parse("{\"ExampleClass\": [\"com/example/I1$Inner\", \"com/example/I1$Inner$SubInner\"]}", DESC);
        List<InjectedInterface> interfaces = rules.interfacesFor("ExampleClass");
        assertEquals(List.of("com/example/I1$Inner", "com/example/I1$Inner$SubInner"),
                interfaces.stream().map(InjectedInterface::internalName).toList());
        assertTrue(rules.targetsClass("ExampleClass"));
    }

    @Test
    void wildcards() {
        InjectedInterface iface =
                singleInterface("\"com/example/HasWild<? extends java.util.Number, ? super java.lang.String, ?>\"");
        assertEquals(3, iface.arguments().size());
        assertEquals(new WildcardTypeArg(WildcardKind.EXTENDS, new ClassTypeArg("java/util/Number", List.of(), List.of())),
                iface.arguments().get(0));
        assertEquals(new WildcardTypeArg(WildcardKind.SUPER, new ClassTypeArg("java/lang/String", List.of(), List.of())),
                iface.arguments().get(1));
        assertEquals(new WildcardTypeArg(WildcardKind.UNBOUNDED, null), iface.arguments().get(2));
    }

    @Test
    void wildcardBoundMayBeGeneric() {
        InjectedInterface iface =
                singleInterface("\"com/example/X<? extends java.util.List<java.lang.String>>\"");
        WildcardTypeArg expectedBound = new WildcardTypeArg(WildcardKind.EXTENDS,
                new ClassTypeArg("java/util/List", List.of(new ClassTypeArg("java/lang/String", List.of(), List.of())), List.of()));
        assertEquals(List.of(expectedBound), iface.arguments());
    }

    @Test
    void dottedBaseNameIsNormalizedButRawTextKeptVerbatim() {
        InjectedInterface iface = singleInterface("\"com.example.Dotted<java.lang.String>\"");
        assertEquals("com/example/Dotted", iface.internalName());
        assertEquals("com.example.Dotted<java.lang.String>", iface.rawText());
        assertEquals(List.of(new ClassTypeArg("java/lang/String", List.of(), List.of())), iface.arguments());
    }

    @Test
    void slashedArgumentFormIsAClassType() {
        InjectedInterface iface = singleInterface("\"com/example/Fn<com/example/Other, T>\"");
        assertEquals(List.of(new ClassTypeArg("com/example/Other", List.of(), List.of()), new TypeVariableArg("T")),
                iface.arguments());
    }

    @Test
    void nestedGenerics() {
        InjectedInterface iface =
                singleInterface("\"com/example/F2<java.util.List<T>, java.util.Map$Entry<java.lang.String, T>>\"");
        ClassTypeArg list = new ClassTypeArg("java/util/List", List.of(new TypeVariableArg("T")), List.of());
        ClassTypeArg entry = new ClassTypeArg("java/util/Map$Entry",
                List.of(new ClassTypeArg("java/lang/String", List.of(), List.of()), new TypeVariableArg("T")),
                List.of());
        assertEquals(List.of(list, entry), iface.arguments());
    }

    @Test
    void whitespaceAroundArgumentsIsInsensitive() {
        InjectedInterface iface = singleInterface("\"com.example.I< T , java.lang.String >\"");
        assertEquals(List.of(new TypeVariableArg("T"), new ClassTypeArg("java/lang/String", List.of(), List.of())),
                iface.arguments());
    }

    @Test
    void duplicateIdenticalEntriesAreSilentlyDeduped() {
        InterfaceInjectionRules rules = InterfaceInjectionRules.parse(
                "{\"com/example/T\": [\"com/example/I<java.lang.String>\", \"com.example.I<java.lang.String>\"]}", DESC);
        List<InjectedInterface> interfaces = rules.interfacesFor("com/example/T");
        assertEquals(1, interfaces.size());
        assertEquals("com/example/I<java.lang.String>", interfaces.get(0).rawText());
    }

    @Test
    void duplicateJsonKeysAccumulateEntries() {
        InterfaceInjectionRules rules =
                InterfaceInjectionRules.parse("{\"com/example/T\": \"a/B\", \"com/example/T\": \"c/D\"}", DESC);
        assertEquals(List.of("a/B", "c/D"),
                rules.interfacesFor("com/example/T").stream().map(InjectedInterface::internalName).toList());
    }

    @Test
    void asMapIsSortedLexicographically() {
        InterfaceInjectionRules rules =
                InterfaceInjectionRules.parse("{\"b/B\": \"c/D\", \"a/A\": \"c/D\", \"a/A2\": \"c/D\"}", DESC);
        assertEquals(List.of("a/A", "a/A2", "b/B"), List.copyOf(rules.asMap().keySet()));
    }

    @Test
    void rejectsDottedKeyWithHint() {
        FacadeRuleException e = assertThrows(FacadeRuleException.class,
                () -> InterfaceInjectionRules.parse("{\"com.example.Dotted\": \"com/example/I\"}", DESC));
        assertTrue(e.getMessage().contains("use slashed internal names (com/example/Target$Inner)"),
                e.getMessage());
        assertEquals(DESC, e.location().file());
    }

    @Test
    void rejectsModuleInfoKey() {
        FacadeRuleException e = assertThrows(FacadeRuleException.class,
                () -> InterfaceInjectionRules.parse("{\"module-info\": \"com/example/I\"}", DESC));
        assertTrue(e.getMessage().contains("module-info"));
    }

    @Test
    void rejectsSelfInjection() {
        FacadeRuleException e = assertThrows(FacadeRuleException.class,
                () -> InterfaceInjectionRules.parse("{\"com/example/T\": \"com/example/T\"}", DESC));
        assertTrue(e.getMessage().contains("cannot inject an interface into itself"), e.getMessage());
    }

    @Test
    void rejectsArrays() {
        FacadeRuleException e = assertThrows(FacadeRuleException.class,
                () -> singleInterface("\"com/example/I<java.lang.String[]>\""));
        assertTrue(e.getMessage().contains("array types are not supported"), e.getMessage());
        assertThrows(FacadeRuleException.class, () -> singleInterface("\"com/example/I[]\""));
    }

    @Test
    void rejectsEmptyTypeArgumentList() {
        FacadeRuleException e = assertThrows(FacadeRuleException.class, () -> singleInterface("\"com/example/I<>\""));
        assertTrue(e.getMessage().contains("blank type argument") || e.getMessage().contains("empty type argument list"),
                e.getMessage());
    }

    @Test
    void rejectsUnbalancedAngleBrackets() {
        assertThrows(FacadeRuleException.class, () -> singleInterface("\"com/example/I<T\""));
        assertThrows(FacadeRuleException.class, () -> singleInterface("\"com/example/I<T>>\""));
        assertThrows(FacadeRuleException.class, () -> singleInterface("\"com/example/I<T>x\""));
    }

    @Test
    void rejectsBlankExpression() {
        assertThrows(FacadeRuleException.class, () -> singleInterface("\"   \""));
    }

    @Test
    void rejectsMalformedWildcard() {
        assertThrows(FacadeRuleException.class, () -> singleInterface("\"com/example/I<? extends>\""));
        assertThrows(FacadeRuleException.class, () -> singleInterface("\"com/example/I<? extends ? super T>\""));
    }

    @Test
    void rejectsBadCharset() {
        assertThrows(FacadeRuleException.class,
                () -> InterfaceInjectionRules.parse("{\"com/example/9T\": \"a/B\"}", DESC));
        assertThrows(FacadeRuleException.class, () -> singleInterface("\"9Bad<T>\""));
    }

    @Test
    void bareIdentifierArgumentsStayVerbatimUntilMergeTime() {
        InjectedInterface iface = singleInterface("\"com/example/I<T, 9Bad>\"");
        assertEquals(List.of(new TypeVariableArg("T"), new TypeVariableArg("9Bad")), iface.arguments());
    }

    @Test
    void rejectsNonStringValue() {
        assertThrows(FacadeRuleException.class,
                () -> InterfaceInjectionRules.parse("{\"com/example/T\": 42}", DESC));
        assertThrows(FacadeRuleException.class,
                () -> InterfaceInjectionRules.parse("{\"com/example/T\": null}", DESC));
        assertThrows(FacadeRuleException.class,
                () -> InterfaceInjectionRules.parse("{\"com/example/T\": [\"a/B\", 1]}", DESC));
    }

    @Test
    void rejectsEmptyArray() {
        FacadeRuleException e = assertThrows(FacadeRuleException.class,
                () -> InterfaceInjectionRules.parse("{\"com/example/T\": []}", DESC));
        assertTrue(e.getMessage().contains("empty interface list"), e.getMessage());
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(FacadeRuleException.class, () -> InterfaceInjectionRules.parse("{", DESC));
        assertThrows(FacadeRuleException.class, () -> InterfaceInjectionRules.parse("not json", DESC));
        assertThrows(FacadeRuleException.class, () -> InterfaceInjectionRules.parse("{\"a\": \"b\"} {}", DESC));
    }

    @Test
    void conflictWithinOneFileNamesBothLocations() {
        FacadeRuleException e = assertThrows(FacadeRuleException.class,
                () -> InterfaceInjectionRules.parse(
                        "{\"com/example/T\": [\"com/example/I<java.lang.String>\", \"com/example/I<java.lang.Integer>\"]}",
                        DESC));
        assertTrue(e.getMessage().contains("conflict"), e.getMessage());
        assertEquals(2, e.getMessage().split(DESC, -1).length - 1);
    }

    @Test
    void parsePathResolvesSourceLocationLines() throws Exception {
        Path dotted = resource("rules/dotted-key.json");
        FacadeRuleException e = assertThrows(FacadeRuleException.class, () -> InterfaceInjectionRules.parse(dotted));
        assertTrue(e.location().file().endsWith("dotted-key.json"));
        assertEquals(2, e.location().line());
    }

    @Test
    void parsePathReadsJstStyleFixture() throws Exception {
        InterfaceInjectionRules rules = InterfaceInjectionRules.parse(resource("rules/plain-and-multi.json"));
        assertEquals(7, rules.asMap().size());
        assertTrue(rules.targetsClass("com/example/PlainTarget"));
        assertTrue(rules.targetsClass("com/MyTarget"));
        assertTrue(rules.targetsClass("ExampleClass"));
        assertTrue(rules.targetsClass("com/example/WildTarget"));
        assertTrue(rules.targetsClass("com/example/NestedTarget"));
        assertTrue(rules.targetsClass("com/example/SlashedArgTarget"));
        assertEquals("com/example/ISomething",
                rules.interfacesFor("com/example/PlainTarget").get(0).internalName());
    }

    @Test
    void conflictFixtureFromResources() throws Exception {
        FacadeRuleException e = assertThrows(FacadeRuleException.class,
                () -> InterfaceInjectionRules.parse(resource("rules/conflict-same-file.json")));
        assertTrue(e.getMessage().contains("com/example/I"), e.getMessage());
    }

    private Path resource(String name) throws Exception {
        return Path.of(getClass().getClassLoader().getResource(name).toURI());
    }
}
