package dev.vfyjxf.gradle.facade.core.fixtures;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class FixtureSources {

    private FixtureSources() {}

    public static final String CONSUMER = """
            public class Consumer {
                public static void main(String[] args) {
                    pkg.api.MyInterface v = new pkg.Library();
                    System.out.println(v.name());
                }
            }
            """;

    public static final String GENERIC_CONSUMER = """
            public class GenericConsumer {
                public static void main(String[] args) {
                    java.util.function.Supplier<String> s = new pkg.GenericLibrary();
                    System.out.println(s.get());
                }
            }
            """;

    public static Map<String, String> core() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("plain.Target", """
                package plain;

                public class Target {
                    private int field = 1;

                    public int value() {
                        return field;
                    }
                }
                """);
        sources.put("gen.Target", """
                package gen;

                public class Target<T> {
                    public T identity(T value) {
                        return value;
                    }
                }
                """);
        sources.put("gen.ExtendsExisting", """
                package gen;

                public class ExtendsExisting extends java.util.ArrayList<String>
                        implements Comparable<ExtendsExisting> {
                    @Override
                    public int compareTo(ExtendsExisting other) {
                        return 0;
                    }
                }
                """);
        sources.put("nested.Outer", """
                package nested;

                public class Outer {
                    private int x = 1;

                    public static class Inner {
                        public int y() {
                            return 2;
                        }
                    }

                    public class Member {
                        public int z() {
                            return x;
                        }
                    }
                }
                """);
        sources.put("rec.Point", """
                package rec;

                public record Point(int x, int y) {
                }
                """);
        sources.put("enm.Color", """
                package enm;

                public enum Color {
                    RED, GREEN, BLUE
                }
                """);
        sources.put("sealed.Shape", """
                package sealed;

                public sealed interface Shape permits Circle {
                    double area();
                }
                """);
        sources.put("sealed.Circle", """
                package sealed;

                public final class Circle implements Shape {
                    private final double radius;

                    public Circle(double radius) {
                        this.radius = radius;
                    }

                    @Override
                    public double area() {
                        return Math.PI * radius * radius;
                    }
                }
                """);
        sources.put("anno.Tag", """
                package anno;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                public @interface Tag {
                    String value();
                }
                """);
        sources.put("anno.Marked", """
                package anno;

                @Tag("fixture")
                @Deprecated
                public class Marked {
                    @Override
                    public String toString() {
                        return "marked";
                    }
                }
                """);
        sources.put("iface.MyInterface", """
                package iface;

                public interface MyInterface {
                    String name();
                }
                """);
        sources.put("arr.ArrayComparable", """
                package arr;

                public class ArrayComparable implements Comparable<String[]> {
                    @Override
                    public int compareTo(String[] other) {
                        return 0;
                    }
                }
                """);
        sources.put("frames.Frames", """
                package frames;

                public class Frames {
                    public int complex(int n) {
                        int total = 0;
                        try {
                            for (int i = 0; i < n; i++) {
                                total += Integer.valueOf(i).intValue();
                            }
                            total += Integer.parseInt("7");
                        } catch (NumberFormatException e) {
                            total = -1;
                        }
                        Runnable done = () -> {
                        };
                        done.run();
                        return total;
                    }
                }
                """);
        sources.put("run.GenTarget", """
                package run;

                public class GenTarget<T> {
                    private final T value;

                    public GenTarget(T value) {
                        this.value = value;
                    }

                    public T get() {
                        return value;
                    }
                }
                """);
        sources.put("run.SupplierTarget", """
                package run;

                public class SupplierTarget {
                    public Object get() {
                        return "supplied";
                    }
                }
                """);
        sources.put("conflict.ConflictTarget", """
                package conflict;

                public class ConflictTarget implements java.util.function.Supplier<Integer> {
                    @Override
                    public Integer get() {
                        return 1;
                    }
                }
                """);
        return Collections.unmodifiableMap(sources);
    }

    public static Map<String, String> pkg() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("pkg.Target", """
                package pkg;

                public class Target<T> {
                    public T identity(T value) {
                        return value;
                    }
                }
                """);
        sources.put("pkg.Library", """
                package pkg;

                public class Library {
                    public String name() {
                        return "library";
                    }
                }
                """);
        sources.put("pkg.GenericLibrary", """
                package pkg;

                public class GenericLibrary {
                    public String get() {
                        return "generic";
                    }
                }
                """);
        sources.put("pkg.api.MyInterface", """
                package pkg.api;

                public interface MyInterface {
                    String name();
                }
                """);
        sources.put("pkg.Other", """
                package pkg;

                public class Other {
                }
                """);
        sources.put("pkg.package-info", """
                @Deprecated
                package pkg;
                """);
        return Collections.unmodifiableMap(sources);
    }

    public static Map<String, String> consumers() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("Consumer", CONSUMER);
        sources.put("GenericConsumer", GENERIC_CONSUMER);
        return Collections.unmodifiableMap(sources);
    }
}
