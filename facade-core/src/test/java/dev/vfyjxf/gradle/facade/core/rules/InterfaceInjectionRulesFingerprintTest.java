package dev.vfyjxf.gradle.facade.core.rules;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InterfaceInjectionRulesFingerprintTest {

    private static InterfaceInjectionRules parse(String json) {
        return InterfaceInjectionRules.parse(json, "fingerprint-test");
    }

    @Test
    void isStableLowercaseSha256Hex() {
        String fingerprint = parse("{\"a/B\": \"c/D\"}").fingerprint();
        assertEquals(64, fingerprint.length());
        assertTrue(fingerprint.matches("[0-9a-f]{64}"), fingerprint);
    }

    @Test
    void equalForWhitespaceDifferingEquivalentJson() throws Exception {
        InterfaceInjectionRules a = InterfaceInjectionRules.parse(Path.of(
                getClass().getClassLoader().getResource("rules/whitespace-a.json").toURI()));
        InterfaceInjectionRules b = InterfaceInjectionRules.parse(Path.of(
                getClass().getClassLoader().getResource("rules/whitespace-b.json").toURI()));
        assertEquals(a.fingerprint(), b.fingerprint());
    }

    @Test
    void equalForCosmeticSpellingDifferences() {
        assertEquals(parse("{\"com/T\": \"com.B\"}").fingerprint(), parse("{\"com/T\": \"com/B\"}").fingerprint());
        assertEquals(parse("{\"com/T\": \"com.B< T >\"}").fingerprint(), parse("{\"com/T\": \"com/B<T>\"}").fingerprint());
    }

    @Test
    void differentForChangedTypeArguments() {
        assertNotEquals(parse("{\"t/T\": \"i/I<java.lang.String>\"}").fingerprint(),
                parse("{\"t/T\": \"i/I<java.lang.Integer>\"}").fingerprint());
    }

    @Test
    void differentForAddedTarget() {
        assertNotEquals(parse("{\"t/T\": \"i/I\"}").fingerprint(),
                parse("{\"t/T\": \"i/I\", \"u/U\": \"i/J\"}").fingerprint());
    }

    @Test
    void differentForRemovedInterface() {
        assertNotEquals(parse("{\"t/T\": [\"i/I\", \"i/J\"]}").fingerprint(),
                parse("{\"t/T\": [\"i/I\"]}").fingerprint());
    }

    @Test
    void differentForReorderedInterfaces() {
        assertNotEquals(parse("{\"t/T\": [\"i/I\", \"i/J\"]}").fingerprint(),
                parse("{\"t/T\": [\"i/J\", \"i/I\"]}").fingerprint());
    }

    @Test
    void differentForWildcardVersusConcrete() {
        assertNotEquals(parse("{\"t/T\": \"i/I<?>\"}").fingerprint(),
                parse("{\"t/T\": \"i/I<java.lang.Object>\"}").fingerprint());
    }

    @Test
    void emptyFingerprintIsStable() {
        assertEquals(InterfaceInjectionRules.empty().fingerprint(), InterfaceInjectionRules.empty().fingerprint());
    }

    @Test
    void computedLazilyAndCached() {
        InterfaceInjectionRules rules = parse("{\"t/T\": \"i/I\"}");
        String first = rules.fingerprint();
        assertSame(first, rules.fingerprint());
    }

    @Test
    void equalRulesFromDifferentParseRunsShareFingerprint() {
        assertEquals(parse("{\"t/T\": [\"i/I<T>\"]}").fingerprint(), parse("{\"t/T\": [\"i/I<T>\"]}").fingerprint());
    }
}
