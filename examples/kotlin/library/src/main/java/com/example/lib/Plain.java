package com.example.lib;

/**
 * A class not named in any facade rule: the transform leaves it (and the rest of the jar
 * entries) byte-for-byte as published.
 */
public class Plain {

    public String name() {
        return "plain";
    }
}
