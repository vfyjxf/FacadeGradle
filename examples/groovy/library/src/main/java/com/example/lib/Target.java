package com.example.lib;

/**
 * A plain class: no {@code implements} clause. It carries {@link #greet()} with the exact
 * signature declared by {@code com.example.lib.api.MyInterface}, so injecting that interface
 * (see {@code app/facade.json}) makes {@code new Target()} assignable to it — at compile time
 * and, because the erased descriptors match, at runtime.
 */
public class Target {

    public String greet() {
        return "Hello from Target!";
    }
}
