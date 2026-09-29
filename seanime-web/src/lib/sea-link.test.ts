import { describe, expect, it, vi } from "vitest"
import { handleSeaLinkClick, isExternalSeaLinkHref } from "./sea-link"

describe("SeaLink URL classification", () => {
    it.each([
        "https://example.com/path",
        "http://127.0.0.1:43211/stream/file",
        "mailto:user@example.com",
        "tel:+123456789",
        "vlc://play/http://127.0.0.1:43211/stream/file",
        "mpv://http://127.0.0.1:43211/stream/file",
        "intent://127.0.0.1:43211/stream/file#Intent;package=org.videolan.vlc;scheme=http;end",
    ])("keeps %s outside the app router", href => {
        expect(isExternalSeaLinkHref(href)).toBe(true)
    })

    it.each([
        "/foo",
        "?query=value",
        "#section",
        "relative/path",
        "javascript:alert(1)",
        "data:text/html,unsafe",
        "file:///etc/passwd",
        "content://provider/item",
        "about:blank",
        "blob:https://example.com/id",
        "android-app://example.app",
    ])("keeps %s out of external handling", href => {
        expect(isExternalSeaLinkHref(href)).toBe(false)
    })
})

describe("SeaLink click handling", () => {
    function clickEvent() {
        let defaultPrevented = false
        return {
            get defaultPrevented() { return defaultPrevented },
            preventDefault: vi.fn(() => { defaultPrevented = true }),
        }
    }

    it("calls the supplied click handler before opening an Android external link", () => {
        const event = clickEvent()
        const calls: string[] = []
        const onClick = vi.fn(() => calls.push("click"))
        const openExternalUrl = vi.fn(() => calls.push("open"))

        handleSeaLinkClick(event, "vlc://play/movie", onClick, openExternalUrl)

        expect(calls).toEqual(["click", "open"])
        expect(event.preventDefault).toHaveBeenCalledOnce()
        expect(openExternalUrl).toHaveBeenCalledWith("vlc://play/movie")
    })

    it("respects a click handler that already canceled navigation", () => {
        const event = clickEvent()
        const openExternalUrl = vi.fn()

        handleSeaLinkClick(event, "https://example.com", currentEvent => currentEvent.preventDefault(), openExternalUrl)

        expect(openExternalUrl).not.toHaveBeenCalled()
    })

    it.each(["  vlc://play/movie  ", "\n https://example.com\t"])("normalizes pasted whitespace in %s before Android handoff", href => {
        const event = clickEvent()
        const openExternalUrl = vi.fn()

        handleSeaLinkClick(event, href, undefined, openExternalUrl)

        expect(event.defaultPrevented).toBe(true)
        expect(openExternalUrl).toHaveBeenCalledWith(href.trim())
    })

    it("leaves the browser in control when no Android bridge is provided", () => {
        const event = clickEvent()

        handleSeaLinkClick(event, "https://example.com")

        expect(event.preventDefault).not.toHaveBeenCalled()
    })
})
