package com.example.app;

import com.example.lib.GenTarget;
import com.example.lib.Plain;
import com.example.lib.Target;
import com.example.lib.api.MyInterface;
import java.util.function.Supplier;

public class Main {

    public static void main(String[] args) {
        // Target has no `implements MyInterface` in the library sources; the facade rule
        // adds the interface to Target's bytecode on this project's classpath.
        MyInterface t = new Target();

        // GenTarget declares `T get()` (erasure ()Ljava/lang/Object;), matching
        // Supplier<T>#get() — so the injected interface actually dispatches at runtime.
        GenTarget<String> g = new GenTarget<>("from GenTarget");
        Supplier<String> s = g;

        // Plain is not named in facade.json and is used as-is.
        String plain = new Plain().name();

        System.out.println(t.greet() + " " + s.get() + " " + plain);
    }
}
