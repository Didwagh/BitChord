package com.music.bitchord.data.sources.mornye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject

/**
 * QuickJS has no DOM globals, so the bootstrap must supply `URL` and
 * `URLSearchParams` itself. Amazon's search parser reads deeplinks with
 * `new URL(path, base)` and swallows the ReferenceError, which used to turn a
 * full results page into "Search returned 0 results" with an HTTP 200.
 */
class MorneyeBootstrapUrlTest {

    private fun withBootstrap(block: (eval: (String) -> String) -> Unit) {
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1
            val scope: ScriptableObject = cx.initStandardObjects()
            // Match the engine the app runs: no host-provided DOM globals.
            cx.evaluateString(
                scope,
                """
                if (typeof URL !== 'undefined') { URL = undefined; }
                if (typeof URLSearchParams !== 'undefined') { URLSearchParams = undefined; }
                var globalThis = this;
                var bridge = function() { return '{"value":null}'; };
                var __mornye_config = {
                  settings: {},
                  permissions: { storage: true, network: ['*'] },
                  sessionEnabled: false
                };
                """.trimIndent(),
                "host-shim.js",
                1,
                null,
            )
            cx.evaluateString(scope, MORNYE_BOOTSTRAP, "bootstrap.js", 1, null)
            block { js -> Context.toString(cx.evaluateString(scope, js, "check.js", 1, null)) }
        } finally {
            Context.exit()
        }
    }

    @Test
    fun `defines URL when the engine has none`() = withBootstrap { eval ->
        assertEquals("function", eval("typeof URL"))
        assertEquals("function", eval("typeof URLSearchParams"))
    }

    @Test
    fun `parses absolute url fields`() = withBootstrap { eval ->
        assertEquals("https:", eval("new URL('https://music.amazon.co.uk:8443/x?q=1#h').protocol"))
        assertEquals("music.amazon.co.uk", eval("new URL('https://music.amazon.co.uk:8443/x').hostname"))
        assertEquals("music.amazon.co.uk:8443", eval("new URL('https://music.amazon.co.uk:8443/x').host"))
        assertEquals("8443", eval("new URL('https://music.amazon.co.uk:8443/x').port"))
        assertEquals("/x", eval("new URL('https://music.amazon.co.uk:8443/x?q=1#h').pathname"))
        assertEquals("?q=1", eval("new URL('https://music.amazon.co.uk:8443/x?q=1#h').search"))
        assertEquals("#h", eval("new URL('https://music.amazon.co.uk:8443/x?q=1#h').hash"))
        assertEquals("https://music.amazon.co.uk:8443", eval("new URL('https://music.amazon.co.uk:8443/x').origin"))
        assertEquals("https://music.amazon.com/", eval("new URL('https://music.amazon.com').href"))
    }

    @Test
    fun `resolves the deeplinks Amazon search returns`() = withBootstrap { eval ->
        assertEquals(
            "/albums/B0ALBUM/track/B0TRACK",
            eval("new URL('/albums/B0ALBUM/track/B0TRACK?trackAsin=B0TRACK', 'https://music.amazon.com').pathname"),
        )
        assertEquals(
            "B0TRACK",
            eval("new URL('/albums/B0ALBUM/track/B0TRACK?trackAsin=B0TRACK', 'https://music.amazon.com').searchParams.get('trackAsin')"),
        )
        assertEquals(
            "/albums/track/B0TRACK",
            eval("new URL('track/B0TRACK', 'https://music.amazon.com/albums/B0ALBUM').pathname"),
        )
        assertEquals(
            "https://music.amazon.in",
            eval("new URL('/albums/B1', 'https://music.amazon.in/x').origin"),
        )
    }

    @Test
    fun `searchParams decodes repeats and encodes back`() = withBootstrap { eval ->
        assertEquals("hello world,2", eval("new URL('https://x/?a=1&b=hello%20world&b=2').searchParams.getAll('b').join(',')"))
        assertEquals("true", eval("String(new URL('https://x/?a=1').searchParams.has('a'))"))
        assertEquals("q=a%20b", eval("new URL('https://x/?q=a b').searchParams.toString()"))
        assertEquals("1", eval("var p = new URLSearchParams('a=1&a=2'); p.get('a')"))
        assertEquals("1,2", eval("var q = new URLSearchParams('a=1&a=2'); q.getAll('a').join(',')"))
    }

    @Test
    fun `canParse reports invalid input instead of throwing`() = withBootstrap { eval ->
        assertTrue(eval("String(URL.canParse('/x', 'https://a.b'))").toBoolean())
        assertFalse(eval("String(URL.canParse('::::'))").toBoolean())
        assertEquals("null", eval("String(URL.parse('::::'))"))
    }
}
