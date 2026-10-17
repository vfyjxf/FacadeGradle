package dev.vfyjxf.gradle.facade.core.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class InterfaceExpressionParser {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private InterfaceExpressionParser() {}

    static InjectedInterface parse(String rawText, String targetInternalName, SourceLocation location) {
        String text = rawText.trim();
        if (text.isEmpty()) {
            throw new FacadeRuleException("blank interface expression for target '" + targetInternalName + "'", location);
        }
        if (text.indexOf('[') >= 0 || text.indexOf(']') >= 0) {
            throw new FacadeRuleException("array types are not supported: '" + rawText + "'", location);
        }
        int firstLt = text.indexOf('<');
        String base;
        List<TypeArg> arguments;
        if (firstLt < 0) {
            base = text;
            arguments = List.of();
        } else {
            int lastGt = text.lastIndexOf('>');
            if (lastGt <= firstLt) {
                throw new FacadeRuleException("unbalanced type arguments in '" + rawText + "'", location);
            }
            base = text.substring(0, firstLt);
            arguments = parseArguments(text.substring(firstLt + 1, lastGt), rawText, location);
            for (int i = lastGt + 1; i < text.length(); i++) {
                if (!Character.isWhitespace(text.charAt(i))) {
                    throw new FacadeRuleException("unexpected content after '>' in '" + rawText + "'", location);
                }
            }
        }
        String internalName = normalizeClassName(base, "interface", rawText, location);
        if (internalName.equals(targetInternalName)) {
            throw new FacadeRuleException("cannot inject an interface into itself: '" + internalName + "'", location);
        }
        return new InjectedInterface(rawText, internalName, arguments, location);
    }

    static String normalizeClassName(String name, String what, String rawText, SourceLocation location) {
        String normalized = name.indexOf('.') >= 0 ? name.replace('.', '/') : name;
        validateInternalName(normalized, what + " '" + name + "' in '" + rawText + "'", location);
        return normalized;
    }

    static void validateInternalName(String internalName, String what, SourceLocation location) {
        if (internalName.isEmpty()) {
            throw new FacadeRuleException("empty " + what, location);
        }
        for (String segment : internalName.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw new FacadeRuleException(
                        "invalid " + what + ": segment '" + segment + "' does not match [A-Za-z_$][A-Za-z0-9_$]*",
                        location);
            }
        }
    }

    private static List<TypeArg> parseArguments(String argumentsText, String rawText, SourceLocation location) {
        List<TypeArg> arguments = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= argumentsText.length(); i++) {
            char c = i == argumentsText.length() ? ',' : argumentsText.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
                if (depth < 0) {
                    throw new FacadeRuleException("unbalanced type arguments in '" + rawText + "'", location);
                }
            } else if (c == ',' && depth == 0) {
                arguments.add(parseArgument(argumentsText.substring(start, i), rawText, location));
                start = i + 1;
            }
        }
        if (arguments.isEmpty()) {
            throw new FacadeRuleException("empty type argument list in '" + rawText + "'", location);
        }
        return List.copyOf(arguments);
    }

    private static TypeArg parseArgument(String raw, String rawText, SourceLocation location) {
        String text = raw.trim();
        if (text.isEmpty()) {
            throw new FacadeRuleException("blank type argument in '" + rawText + "'", location);
        }
        if (text.indexOf('[') >= 0 || text.indexOf(']') >= 0) {
            throw new FacadeRuleException("array types are not supported: '" + rawText + "'", location);
        }
        if (text.startsWith("?")) {
            return parseWildcard(text, rawText, location);
        }
        if (text.indexOf('.') < 0 && text.indexOf('/') < 0) {
            return new TypeVariableArg(text);
        }
        int firstLt = text.indexOf('<');
        String name = text;
        List<TypeArg> nested;
        if (firstLt >= 0) {
            int lastGt = text.lastIndexOf('>');
            if (lastGt <= firstLt) {
                throw new FacadeRuleException("unbalanced type arguments in '" + rawText + "'", location);
            }
            name = text.substring(0, firstLt);
            nested = parseArguments(text.substring(firstLt + 1, lastGt), rawText, location);
            for (int i = lastGt + 1; i < text.length(); i++) {
                if (!Character.isWhitespace(text.charAt(i))) {
                    throw new FacadeRuleException("unexpected content after '>' in '" + rawText + "'", location);
                }
            }
        } else {
            nested = List.of();
        }
        String internalName = normalizeClassName(name, "type argument", rawText, location);
        return new ClassTypeArg(internalName, nested, List.of());
    }

    private static TypeArg parseWildcard(String text, String rawText, SourceLocation location) {
        String rest = text.substring(1).trim();
        if (rest.isEmpty()) {
            return new WildcardTypeArg(WildcardKind.UNBOUNDED, null);
        }
        WildcardKind kind;
        String boundText;
        if (rest.startsWith("extends ")) {
            kind = WildcardKind.EXTENDS;
            boundText = rest.substring("extends ".length()).trim();
        } else if (rest.startsWith("super ")) {
            kind = WildcardKind.SUPER;
            boundText = rest.substring("super ".length()).trim();
        } else {
            throw new FacadeRuleException("malformed wildcard type argument '" + text + "' in '" + rawText + "'", location);
        }
        if (boundText.isEmpty()) {
            throw new FacadeRuleException("wildcard without bound in '" + rawText + "'", location);
        }
        TypeArg bound = parseArgument(boundText, rawText, location);
        if (bound instanceof WildcardTypeArg) {
            throw new FacadeRuleException("wildcard bound cannot be a wildcard in '" + rawText + "'", location);
        }
        return new WildcardTypeArg(kind, bound);
    }
}
