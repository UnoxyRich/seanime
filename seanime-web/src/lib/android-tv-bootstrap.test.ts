import { afterEach, describe, expect, it, vi } from "vitest"
import { requestAndroidTVBridgeToken, type AndroidTVBootstrap } from "./android-tv-bootstrap"

afterEach(() => vi.useRealTimers())

function host(): AndroidTVBootstrap {
    return { onmessage: null, postMessage: vi.fn() }
}

describe("Android TV bridge bootstrap", () => {
    it("waits for the host reply and releases its handler", async () => {
        const bootstrap = host()
        const result = requestAndroidTVBridgeToken(bootstrap)
        expect(bootstrap.postMessage).toHaveBeenCalledWith("seanime-tv-bootstrap-v1")
        bootstrap.onmessage?.({ data: JSON.stringify({ type: "seanime-tv-bootstrap-v1", token: "activity-token" }) })
        await expect(result).resolves.toBe("activity-token")
        expect(bootstrap.onmessage).toBeNull()
    })

    it("ignores invalid replies before accepting the expected response", async () => {
        const bootstrap = host()
        const result = requestAndroidTVBridgeToken(bootstrap)
        for (const data of ["{", "null", '{}', '{"type":"another-message","token":"wrong"}',
            '{"type":"seanime-tv-bootstrap-v1","token":3}', '{"type":"seanime-tv-bootstrap-v1","token":"  "}',
            JSON.stringify({ type: "seanime-tv-bootstrap-v1", token: "x".repeat(1025) })]) {
            bootstrap.onmessage?.({ data })
            expect(bootstrap.onmessage).not.toBeNull()
        }
        bootstrap.onmessage?.({ data: JSON.stringify({ type: "seanime-tv-bootstrap-v1", token: "valid" }) })
        await expect(result).resolves.toBe("valid")
    })

    it("times out and ignores a late response", async () => {
        vi.useFakeTimers()
        const bootstrap = host()
        const result = requestAndroidTVBridgeToken(bootstrap, 500)
        const late = bootstrap.onmessage
        await vi.advanceTimersByTimeAsync(500)
        late?.({ data: JSON.stringify({ type: "seanime-tv-bootstrap-v1", token: "late" }) })
        await expect(result).resolves.toBe("")
        expect(bootstrap.onmessage).toBeNull()
        expect(vi.getTimerCount()).toBe(0)
    })

    it("cleans up when posting fails", async () => {
        vi.useFakeTimers()
        const bootstrap = host()
        bootstrap.postMessage = () => { throw new Error("host unavailable") }
        await expect(requestAndroidTVBridgeToken(bootstrap)).resolves.toBe("")
        expect(bootstrap.onmessage).toBeNull()
        expect(vi.getTimerCount()).toBe(0)
    })

    it("accepts a synchronous reply after the handler has been registered", async () => {
        const bootstrap = host()
        bootstrap.postMessage = () => bootstrap.onmessage?.({ data: JSON.stringify({ type: "seanime-tv-bootstrap-v1", token: "ready" }) })
        await expect(requestAndroidTVBridgeToken(bootstrap)).resolves.toBe("ready")
    })
})
