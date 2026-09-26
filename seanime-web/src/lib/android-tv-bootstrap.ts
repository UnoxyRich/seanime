export type AndroidTVBootstrap = {
    onmessage: ((event: { data: string }) => void) | null
    postMessage: (message: string) => void
}

/** Obtain the per-activity token through Android's origin-checked reply channel. */
export function requestAndroidTVBridgeToken(bootstrap: AndroidTVBootstrap, timeoutMs = 5_000): Promise<string> {
    return new Promise(resolve => {
        let settled = false
        const finish = (token: string) => {
            if (settled) return
            settled = true
            clearTimeout(timer)
            bootstrap.onmessage = null
            resolve(token)
        }
        const timer = setTimeout(() => finish(""), timeoutMs)
        bootstrap.onmessage = event => {
            try {
                const value = JSON.parse(event.data)
                if (!value || typeof value !== "object") return
                const message = value as Record<string, unknown>
                if (message.type !== "seanime-tv-bootstrap-v1" || typeof message.token !== "string" ||
                    !message.token.trim() || message.token.length > 1024) return
                finish(message.token)
            } catch {
                // Ignore unrelated or malformed replies; the timeout is bounded.
            }
        }
        try {
            bootstrap.postMessage("seanime-tv-bootstrap-v1")
        } catch {
            finish("")
        }
    })
}
