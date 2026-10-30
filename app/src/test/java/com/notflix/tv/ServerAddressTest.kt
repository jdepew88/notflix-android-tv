package com.notflix.tv

import com.notflix.tv.data.ParsedAddress
import com.notflix.tv.data.ServerAddress
import com.notflix.tv.data.ServerOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {
    private fun valid(input: String) = ServerAddress.parse(input) as? ParsedAddress.Valid
        ?: throw AssertionError("expected $input to be valid, got ${ServerAddress.parse(input)}")

    private fun invalid(input: String): String = (ServerAddress.parse(input) as? ParsedAddress.Invalid)?.reason
        ?: throw AssertionError("expected $input to be invalid, got ${ServerAddress.parse(input)}")

    @Test fun bareIpv4GetsTheSuggestedSchemeAndBothNotflixPorts() {
        val expected = ParsedAddress.Valid("http://192.168.1.33:8420", suggested = true, addedPort = true, alternatives = listOf("http://192.168.1.33:4119"))
        assertEquals(expected, ServerAddress.parse("192.168.1.33"))
        assertEquals(expected, ServerAddress.parse("  192.168.1.33/ "))
        assertEquals(listOf("http://192.168.1.33:8420", "http://192.168.1.33:4119"), expected.candidates)
    }

    @Test fun anExplicitPortOrFullUrlIsTheOnlyCandidate() {
        assertEquals(listOf("http://192.168.1.33:8421"), valid("192.168.1.33:8421").candidates)
        assertEquals(listOf("http://192.168.1.33:4119"), valid("http://192.168.1.33:4119").candidates)
        assertEquals(listOf("https://notflix.example.com"), valid("https://notflix.example.com").candidates)
    }

    @Test fun anyPrivateRangeAndSubnetSizeWorks() {
        // Nothing assumes 192.168.x.0/24: 10/8, 172.16/12, CGNAT-style and odd ranges all parse the same way.
        listOf("10.20.0.11", "172.20.5.9", "100.64.3.2", "192.168.200.254", "10.255.255.1").forEach {
            assertEquals(listOf("http://$it:8420", "http://$it:4119"), valid(it).candidates)
        }
    }

    @Test fun customPortWithoutSchemeIsStillMarkedAsSuggested() {
        assertEquals(ParsedAddress.Valid("http://192.168.1.33:8080", suggested = true), ServerAddress.parse("192.168.1.33:8080"))
    }

    @Test fun completeUrlsAreUsedExactlyAsEntered() {
        assertEquals(ParsedAddress.Valid("http://192.168.1.33:4119", suggested = false), ServerAddress.parse("http://192.168.1.33:4119"))
        assertEquals(ParsedAddress.Valid("http://notflix.local:4119", suggested = false), ServerAddress.parse("http://notflix.local:4119/"))
        // No port added to a full URL: https means 443, http means 80.
        assertEquals(ParsedAddress.Valid("https://notflix.example.com", suggested = false), ServerAddress.parse("https://notflix.example.com"))
        assertEquals(ParsedAddress.Valid("http://notflix.example.com", suggested = false), ServerAddress.parse("HTTP://Notflix.Example.com"))
        assertEquals("https://proxy.example.com/notflix", valid("https://proxy.example.com/notflix/").url)
    }

    @Test fun hostnames() {
        assertEquals("http://notflix.local:8420", valid("notflix.local").url)
        assertEquals("http://media-pc:8420", valid("media-pc").url)
        assertEquals("http://notflix.example.com:8420", valid("notflix.example.com").url)
        assertEquals("http://media-pc:4119", valid("media-pc").alternatives.single())
    }

    @Test fun ipv6InUrlNotation() {
        assertEquals(listOf("http://[fd00::12]:8420", "http://[fd00::12]:4119"), valid("[fd00::12]").candidates)
        assertEquals("http://[fd00::12]:8080", valid("[fd00::12]:8080").url)
        assertEquals("http://[2001:db8::1]:8420", valid("2001:db8::1").url)
        assertEquals(ParsedAddress.Valid("http://[fd00::12]:4119", false), ServerAddress.parse("http://[fd00::12]:4119"))
        assertTrue(invalid("fe80::1%wlan0").contains("zone"))
    }

    @Test fun invalidAddresses() {
        assertTrue(invalid("").isNotEmpty())
        assertTrue(invalid("192.168.1.33:99999").contains("port"))
        assertTrue(invalid("192.168.1.33:0").contains("port"))
        assertTrue(invalid("192.168.1.33:abc").contains("port"))
        assertTrue(invalid("http://192.168.1.33:70000").contains("port"))
        assertTrue(invalid("999.1.1.1").contains("IPv4"))
        assertTrue(invalid("192.168.1").contains("IPv4"))
        assertTrue(invalid("my server").contains("spaces"))
        assertTrue(invalid("ftp://192.168.1.33").contains("http"))
        assertTrue(invalid("http://user:pw@192.168.1.33").contains("password"))
        assertTrue(invalid("http://192.168.1.33:4119/?x=1").isNotEmpty())
        assertTrue(invalid("0.0.0.0").isNotEmpty())
        assertTrue(invalid("http://").isNotEmpty())
    }

    @Test fun originsAreSchemeHostAndPort() {
        val a = ServerOrigin.of("http://10.0.0.5:4119")!!
        assertEquals(a, ServerOrigin.of("http://10.0.0.5:4119/sub"))
        assertTrue(a != ServerOrigin.of("http://10.0.0.5:4129"))
        assertTrue(a != ServerOrigin.of("https://10.0.0.5:4119"))
        // Stored form round-trips, IPv6 included.
        val v6 = ServerOrigin.of("http://[fd00::12]:4119")!!
        assertEquals(v6, ServerOrigin.of(v6.toString()))
        assertEquals(a, ServerOrigin.of(a.toString()))
    }
}
