const blockedProtocols = new Set([
    "about",
    "android-app",
    "blob",
    "content",
    "data",
    "file",
    "javascript",
])

/** Returns true for absolute URLs that should stay outside the app router. */
export function isExternalSeaLinkHref(href: string | undefined): boolean {
    if (!href) return false

    const match = href.trim().match(/^([a-z][a-z\d+.-]*):/i)
    if (!match) return false

    return !blockedProtocols.has(match[1].toLowerCase())
}

type SeaLinkClickEvent = {
    defaultPrevented: boolean
    preventDefault: () => void
}

/** Runs link callbacks before handing an external URL to Android's host bridge. */
export function handleSeaLinkClick<T extends SeaLinkClickEvent>(
    event: T,
    href: string | undefined,
    onClick?: (event: T) => void,
    openExternalUrl?: (url: string) => void,
) {
    onClick?.(event)

    if (event.defaultPrevented || !href || !openExternalUrl || !isExternalSeaLinkHref(href)) return

    event.preventDefault()
    openExternalUrl(href.trim())
}
