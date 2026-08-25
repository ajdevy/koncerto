package com.flexsentlabs.koncerto.demo.recorder.api

import org.junit.jupiter.api.Test

class JsonEncodingTest {

    @Test
    fun `encodes scalars`() {
        assert(JsonEncoding.encode(null) == "null")
        assert(JsonEncoding.encode(true) == "true")
        assert(JsonEncoding.encode(42) == "42")
        assert(JsonEncoding.encode(42L) == "42")
        assert(JsonEncoding.encode(1.5) == "1.5")
        assert(JsonEncoding.encode(1.5f) == "1.5")
        assert(JsonEncoding.encode("text") == "\"text\"")
    }

    @Test
    fun `encodes short and byte widths as numbers`() {
        assert(JsonEncoding.encode(7.toShort()) == "7")
        assert(JsonEncoding.encode(7.toByte()) == "7")
    }

    @Test
    fun `non-finite floating point degrades to null rather than invalid JSON`() {
        assert(JsonEncoding.encode(Double.NaN) == "null")
        assert(JsonEncoding.encode(Double.POSITIVE_INFINITY) == "null")
        assert(JsonEncoding.encode(Float.NaN) == "null")
        assert(JsonEncoding.encode(Float.NEGATIVE_INFINITY) == "null")
    }

    @Test
    fun `encodes maps and lists including nesting`() {
        assert(JsonEncoding.encode(linkedMapOf("a" to 1, "b" to listOf(1, "x"))) == "{\"a\":1,\"b\":[1,\"x\"]}")
        assert(JsonEncoding.encode(emptyMap<String, Any>()) == "{}")
        assert(JsonEncoding.encode(emptyList<Any>()) == "[]")
    }

    @Test
    fun `a non-string map key is rendered as text`() {
        assert(JsonEncoding.encode(mapOf(1 to "a")) == "{\"1\":\"a\"}")
        assert(JsonEncoding.encode(mapOf(null to "a")) == "{\"\":\"a\"}")
    }

    @Test
    fun `escapes a quote and a backslash`() {
        assert(JsonEncoding.encode("a\"b") == "\"a\\\"b\"")
        assert(JsonEncoding.encode("a\\b") == "\"a\\\\b\"")
    }

    @Test
    fun `escapes newline tab and carriage return`() {
        assert(JsonEncoding.encode("a\nb\tc\rd") == "\"a\\nb\\tc\\rd\"")
    }

    @Test
    fun `escapes other control characters as unicode`() {
        assert(JsonEncoding.encode("a" + 1.toChar() + "b") == "\"a\\u0001b\"")
    }

    @Test
    fun `an unrecognised type falls back to its string form`() {
        assert(JsonEncoding.encode(java.math.BigInteger("9")) == "\"9\"")
    }
}
