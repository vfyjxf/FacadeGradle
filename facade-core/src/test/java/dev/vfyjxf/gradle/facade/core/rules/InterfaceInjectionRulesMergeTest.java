package dev.vfyjxf.gradle.facade.core.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InterfaceInjectionRulesMergeTest {

    private static InterfaceInjectionRules parse(String desc, String json) {
        return InterfaceInjectionRules.parse(json, desc);
    }

    @Test
    void mergeNoSetsIsEmpty() {
        InterfaceInjectionRules merged = InterfaceInjectionRules.merge();
        assertTrue(merged.isEmpty());
        assertEquals(InterfaceInjectionRules.empty().fingerprint(), merged.fingerprint());
    }

    @Test
    void mergeSingleSetIsEquivalent() {
        InterfaceInjectionRules set = parse("a.json", "{\"t/T\": [\"a/B\", \"c/D\"]}");
        InterfaceInjectionRules merged = InterfaceInjectionRules.merge(set);
        assertEquals(set.asMap().keySet(), merged.asMap().keySet());
        assertEquals(set.fingerprint(), merged.fingerprint());
    }

    @Test
    void mergeAppendsLaterSetsAfterEarlier() {
        InterfaceInjectionRules first = parse("a.json", "{\"t/T\": [\"a/B\", \"b/C\"]}");
        InterfaceInjectionRules second = parse("b.json", "{\"t/T\": [\"d/E\"]}");
        InterfaceInjectionRules merged = InterfaceInjectionRules.merge(first, second);
        assertEquals(List.of("a/B", "b/C", "d/E"),
                merged.interfacesFor("t/T").stream().map(InjectedInterface::internalName).toList());
    }

    @Test
    void mergeDedupesAcrossFilesKeepingFirstSource() {
        InterfaceInjectionRules first = parse("a.json", "{\"t/T\": [\"i/I<java.lang.String>\"]}");
        InterfaceInjectionRules second = parse("b.json", "{\"t/T\": [\"i/I<java.lang.String>\"]}");
        InterfaceInjectionRules merged = InterfaceInjectionRules.merge(first, second);
        List<InjectedInterface> interfaces = merged.interfacesFor("t/T");
        assertEquals(1, interfaces.size());
        assertEquals("a.json", interfaces.get(0).source().file());
    }

    @Test
    void mergeConflictAcrossFilesIsRejected() {
        InterfaceInjectionRules first = parse("a.json", "{\"t/T\": [\"i/I<java.lang.String>\"]}");
        InterfaceInjectionRules second = parse("b.json", "{\"t/T\": [\"i/I<java.lang.Integer>\"]}");
        FacadeRuleException e = assertThrows(FacadeRuleException.class,
                () -> InterfaceInjectionRules.merge(first, second));
        assertTrue(e.getMessage().contains("conflict"), e.getMessage());
        assertTrue(e.getMessage().contains("a.json"));
        assertTrue(e.getMessage().contains("b.json"));
    }

    @Test
    void mergeDisjointTargetsKeepsBoth() {
        InterfaceInjectionRules first = parse("a.json", "{\"t/One\": \"i/A\"}");
        InterfaceInjectionRules second = parse("b.json", "{\"t/Two\": \"i/B\"}");
        InterfaceInjectionRules merged = InterfaceInjectionRules.merge(first, second);
        assertTrue(merged.targetsClass("t/One"));
        assertTrue(merged.targetsClass("t/Two"));
        assertEquals(List.of("t/One", "t/Two"), List.copyOf(merged.asMap().keySet()));
    }

    @Test
    void mergedInterfacesForUnknownTargetIsEmpty() {
        InterfaceInjectionRules merged = InterfaceInjectionRules.merge(parse("a.json", "{\"t/T\": \"i/A\"}"));
        assertTrue(merged.interfacesFor("nope/Nope").isEmpty());
    }

    @Test
    void mergeMatchesEquivalentSingleFileFingerprint() {
        InterfaceInjectionRules first = parse("a.json", "{\"t/T\": [\"i/A\", \"i/B\"]}");
        InterfaceInjectionRules second = parse("b.json", "{\"u/U\": \"i/C<java.lang.String, T>\"}");
        InterfaceInjectionRules merged = InterfaceInjectionRules.merge(first, second);
        InterfaceInjectionRules equivalent = parse("combined.json",
                "{\"t/T\": [\"i/A\", \"i/B\"], \"u/U\": \"i/C<java.lang.String, T>\"}");
        assertEquals(equivalent.fingerprint(), merged.fingerprint());
    }

    @Test
    void mergeThreeSetsOrderStable() {
        InterfaceInjectionRules a = parse("a.json", "{\"t/T\": \"i/A\"}");
        InterfaceInjectionRules b = parse("b.json", "{\"t/T\": \"i/B\"}");
        InterfaceInjectionRules c = parse("c.json", "{\"t/T\": \"i/A\"}");
        InterfaceInjectionRules merged = InterfaceInjectionRules.merge(a, b, c);
        assertEquals(List.of("i/A", "i/B"),
                merged.interfacesFor("t/T").stream().map(InjectedInterface::internalName).toList());
        assertEquals("a.json", merged.interfacesFor("t/T").get(0).source().file());
    }
}
