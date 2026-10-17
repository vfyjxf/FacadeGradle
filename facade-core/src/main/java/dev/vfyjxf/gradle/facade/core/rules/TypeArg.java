package dev.vfyjxf.gradle.facade.core.rules;

public sealed interface TypeArg permits ClassTypeArg, TypeVariableArg, WildcardTypeArg, ArrayTypeArg {}
