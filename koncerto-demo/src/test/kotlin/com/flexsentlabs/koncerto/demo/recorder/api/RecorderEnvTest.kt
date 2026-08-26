package com.flexsentlabs.koncerto.demo.recorder.api

import org.junit.jupiter.api.Test

class RecorderEnvTest {

    @Test
    fun `parses key value lines`() {
        val parsed = RecorderEnv.parseKeyValues("API_TOKEN=abc123\nUSER=demo")

        assert(parsed == mapOf("API_TOKEN" to "abc123", "USER" to "demo"))
    }

    @Test
    fun `skips blanks and comments`() {
        val parsed = RecorderEnv.parseKeyValues("\n# a comment\n  \nA=1\n")

        assert(parsed == mapOf("A" to "1"))
    }

    @Test
    fun `only the first equals splits so tokens survive`() {
        val parsed = RecorderEnv.parseKeyValues("JWT=eyJ0=padding==")

        assert(parsed == mapOf("JWT" to "eyJ0=padding=="))
    }

    @Test
    fun `strips matching surrounding quotes`() {
        val parsed = RecorderEnv.parseKeyValues("A=\"quoted\"\nB='single'\nC=\"unbalanced\nD=\"\"")

        assert(parsed["A"] == "quoted")
        assert(parsed["B"] == "single")
        assert(parsed["C"] == "\"unbalanced")
        assert(parsed["D"] == "")
    }

    @Test
    fun `ignores lines with no key or no separator`() {
        val parsed = RecorderEnv.parseKeyValues("=orphan\nnoseparator\n   =x\nGOOD=1")

        assert(parsed == mapOf("GOOD" to "1"))
    }

    @Test
    fun `trims surrounding whitespace on both sides`() {
        val parsed = RecorderEnv.parseKeyValues("  A  =  1  ")

        assert(parsed == mapOf("A" to "1"))
    }
}
