package com.example.lib;

/**
 * A generic class. {@link #get()} is declared as {@code T get()} whose erasure is
 * {@code ()Ljava/lang/Object;} — exactly the erasure of {@code java.util.function.Supplier#get()}.
 * That is why the rule {@code "com/example/lib/GenTarget": ["java/util/function/Supplier<T>"]}
 * works for actual runtime dispatch, while injecting e.g. a {@code Supplier<String>} whose
 * {@code get()} erases to {@code ()Ljava/lang/String;} would fail with AbstractMethodError.
 */
public class GenTarget<T> {

    private final T value;

    public GenTarget(T value) {
        this.value = value;
    }

    public T get() {
        return value;
    }
}
