package com.example.lib.api;

/**
 * Interface that {@code com.example.lib.Target} does <em>not</em> declare in its sources. The
 * facade rule in {@code app/facade.json} adds {@code implements MyInterface} to {@code Target}'s
 * bytecode on the consumer's classpath, so this assignment compiles and dispatches:
 *
 * <pre>{@code
 * MyInterface t = new Target(); // only valid with facade injection
 * }</pre>
 */
public interface MyInterface {

    String greet();
}
