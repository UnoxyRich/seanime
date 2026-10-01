package app.seanime.tv.platform

import org.junit.Assert.*
import org.junit.Test

class AuthBrowserPolicyTest {
    @Test fun onlyExactSecureProviderOriginsCanOwnLoginPresentation() {
        listOf("https://anilist.co/login", "https://myanimelist.net/v1/oauth2/authorize?state=fixture", "https://www.myanimelist.net:443/login.php")
            .forEach { assertTrue(it, AuthBrowserPolicy.isProviderUrl(it)) }
        listOf("http://anilist.co/login", "https://anilist.co.evil.invalid", "https://evil.invalid/anilist.co", "https://user:pass@anilist.co/",
            "https://anilist.co:8443/login", "file:///login", "javascript:alert(1)", "https://127.0.0.1:43211/", "not a url")
            .forEach { assertFalse(it, AuthBrowserPolicy.isProviderUrl(it)) }
    }
}
