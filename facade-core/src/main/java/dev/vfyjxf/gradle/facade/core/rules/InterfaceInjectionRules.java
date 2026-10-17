package dev.vfyjxf.gradle.facade.core.rules;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InterfaceInjectionRules {

    private static final int KEY_LOOKUP_UNKNOWN_LINE = -1;

    private final NavigableMap<String, List<InjectedInterface>> byTarget;
    private String fingerprint;

    private InterfaceInjectionRules(TreeMap<String, List<InjectedInterface>> byTarget) {
        this.byTarget = Collections.unmodifiableNavigableMap(byTarget);
    }

    public static InterfaceInjectionRules empty() {
        return new InterfaceInjectionRules(new TreeMap<>());
    }

    public static InterfaceInjectionRules parse(Path jsonFile) throws IOException {
        Objects.requireNonNull(jsonFile, "jsonFile");
        return parse(Files.readString(jsonFile, StandardCharsets.UTF_8), jsonFile.toString());
    }

    public static InterfaceInjectionRules parse(String json, String sourceDescription) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(sourceDescription, "sourceDescription");
        Collector collector = new Collector();
        JsonReader reader = new JsonReader(new StringReader(json));
        int[] searchCursor = {0};
        try {
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                SourceLocation location =
                        new SourceLocation(sourceDescription, findKeyLine(json, key, searchCursor));
                validateTargetKey(key, location);
                JsonToken token = reader.peek();
                if (token == JsonToken.STRING) {
                    collector.addInterface(key, reader.nextString(), location);
                } else if (token == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray();
                    boolean any = false;
                    while (reader.hasNext()) {
                        JsonToken element = reader.peek();
                        if (element != JsonToken.STRING) {
                            throw new FacadeRuleException(
                                    "expected a string interface name for target '" + key + "', got " + element,
                                    location);
                        }
                        collector.addInterface(key, reader.nextString(), location);
                        any = true;
                    }
                    reader.endArray();
                    if (!any) {
                        throw new FacadeRuleException("empty interface list for target '" + key + "'", location);
                    }
                } else {
                    throw new FacadeRuleException(
                            "expected a string or an array of strings for target '" + key + "', got " + token,
                            location);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new FacadeRuleException(
                        "unexpected trailing content in " + sourceDescription,
                        new SourceLocation(sourceDescription, KEY_LOOKUP_UNKNOWN_LINE));
            }
            return collector.build();
        } catch (FacadeRuleException e) {
            throw e;
        } catch (IOException | IllegalStateException | NumberFormatException e) {
            int line = e instanceof MalformedJsonException ? lineFromMessage(e.getMessage()) : KEY_LOOKUP_UNKNOWN_LINE;
            throw new FacadeRuleException(
                    "malformed JSON in " + sourceDescription + (line >= 0 ? " at line " + line : "") + ": " + e.getMessage(),
                    e,
                    new SourceLocation(sourceDescription, line));
        }
    }

    private static int lineFromMessage(String message) {
        if (message == null) {
            return KEY_LOOKUP_UNKNOWN_LINE;
        }
        Matcher matcher = Pattern.compile("at line (\\d+)").matcher(message);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : KEY_LOOKUP_UNKNOWN_LINE;
    }

    public static InterfaceInjectionRules merge(InterfaceInjectionRules... sets) {
        Objects.requireNonNull(sets, "sets");
        Collector collector = new Collector();
        for (InterfaceInjectionRules set : sets) {
            Objects.requireNonNull(set, "sets must not contain null");
            for (Map.Entry<String, List<InjectedInterface>> entry : set.byTarget.entrySet()) {
                for (InjectedInterface iface : entry.getValue()) {
                    collector.addEntry(entry.getKey(), iface, iface.source());
                }
            }
        }
        return collector.build();
    }

    public boolean isEmpty() {
        return byTarget.isEmpty();
    }

    public boolean targetsClass(String internalName) {
        return internalName != null && byTarget.containsKey(internalName);
    }

    public List<InjectedInterface> interfacesFor(String targetInternalName) {
        return byTarget.getOrDefault(targetInternalName, List.of());
    }

    public NavigableMap<String, List<InjectedInterface>> asMap() {
        return byTarget;
    }

    public String fingerprint() {
        String cached = fingerprint;
        if (cached == null) {
            StringBuilder canonical = new StringBuilder();
            for (Map.Entry<String, List<InjectedInterface>> entry : byTarget.entrySet()) {
                canonical.append(entry.getKey()).append('\n');
                for (InjectedInterface iface : entry.getValue()) {
                    canonical.append(canonical(iface)).append('\n');
                }
            }
            cached = sha256Hex(canonical.toString());
            fingerprint = cached;
        }
        return cached;
    }

    private static void validateTargetKey(String key, SourceLocation location) {
        if (key.equals("module-info")) {
            throw new FacadeRuleException("cannot inject interfaces into module-info", location);
        }
        if (key.indexOf('.') >= 0) {
            throw new FacadeRuleException(
                    "invalid target class '" + key + "': use slashed internal names (com/example/Target$Inner)",
                    location);
        }
        InterfaceExpressionParser.validateInternalName(key, "target class '" + key + "'", location);
    }

    private static String canonical(InjectedInterface iface) {
        StringBuilder sb = new StringBuilder(iface.internalName());
        appendCanonicalArguments(sb, iface.arguments());
        return sb.toString();
    }

    private static void appendCanonicalArguments(StringBuilder sb, List<TypeArg> arguments) {
        if (arguments.isEmpty()) {
            return;
        }
        sb.append('<');
        for (int i = 0; i < arguments.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(canonical(arguments.get(i)));
        }
        sb.append('>');
    }

    private static String canonical(TypeArg arg) {
        return switch (arg) {
            case ClassTypeArg classType -> {
                StringBuilder sb = new StringBuilder(classType.internalName());
                appendCanonicalArguments(sb, classType.arguments());
                for (Suffix suffix : classType.suffixes()) {
                    sb.append('.').append(suffix.simpleName());
                    appendCanonicalArguments(sb, suffix.arguments());
                }
                yield sb.toString();
            }
            case TypeVariableArg typeVariable -> typeVariable.name();
            case WildcardTypeArg wildcard -> switch (wildcard.kind()) {
                case UNBOUNDED -> "?";
                case EXTENDS -> "? extends " + canonical(wildcard.bound());
                case SUPER -> "? super " + canonical(wildcard.bound());
            };
            case ArrayTypeArg array -> "[".repeat(array.dimensions()) + canonical(array.component());
        };
    }

    private static String sha256Hex(String text) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not provide SHA-256", e);
        }
        return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static int findKeyLine(String json, String key, int[] cursor) {
        String needle = "\"" + key + "\"";
        int index = json.indexOf(needle, cursor[0]);
        while (index >= 0) {
            int after = index + needle.length();
            while (after < json.length() && Character.isWhitespace(json.charAt(after))) {
                after++;
            }
            if (after < json.length() && json.charAt(after) == ':') {
                break;
            }
            index = json.indexOf(needle, index + 1);
        }
        if (index < 0) {
            return KEY_LOOKUP_UNKNOWN_LINE;
        }
        cursor[0] = index + needle.length();
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (json.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static final class Collector {

        private final LinkedHashMap<String, List<InjectedInterface>> targets = new LinkedHashMap<>();

        List<InjectedInterface> target(String key) {
            return targets.computeIfAbsent(key, k -> new ArrayList<>());
        }

        void addInterface(String key, String rawText, SourceLocation location) {
            addEntry(key, InterfaceExpressionParser.parse(rawText, key, location), location);
        }

        void addEntry(String key, InjectedInterface candidate, SourceLocation location) {
            List<InjectedInterface> existing = target(key);
            for (InjectedInterface prior : existing) {
                if (prior.internalName().equals(candidate.internalName())) {
                    if (prior.arguments().equals(candidate.arguments())) {
                        return;
                    }
                    throw new FacadeRuleException(
                            "conflicting declarations for interface '" + candidate.internalName()
                                    + "' on target '" + key + "': " + canonical(prior) + " at " + prior.source()
                                    + " conflicts with " + canonical(candidate) + " at " + candidate.source(),
                            location);
                }
            }
            existing.add(candidate);
        }

        InterfaceInjectionRules build() {
            TreeMap<String, List<InjectedInterface>> sorted = new TreeMap<>();
            for (Map.Entry<String, List<InjectedInterface>> entry : targets.entrySet()) {
                List<InjectedInterface> interfaces = entry.getValue();
                if (!interfaces.isEmpty()) {
                    sorted.put(entry.getKey(), List.copyOf(interfaces));
                }
            }
            return new InterfaceInjectionRules(sorted);
        }
    }
}
