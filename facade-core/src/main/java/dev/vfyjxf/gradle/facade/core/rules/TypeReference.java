package dev.vfyjxf.gradle.facade.core.rules;

/**
 * A reference-type position of a class signature (type parameter bounds, superclass,
 * superinterfaces, type arguments). Wildcards can never appear at reference positions,
 * which the permits list makes unrepresentable. Declared in the rules package rather
 * than next to the signature engine because the platform only allows a sealed type to
 * permit subtypes in the same package when compiled into the unnamed module.
 */
public sealed interface TypeReference permits ClassTypeArg, TypeVariableArg, ArrayTypeArg {}
