import { describe, expect, it } from "vitest"

import { cloneApiData } from "./clone-api-data"

describe("cloneApiData on legacy WebView", () => {
    it("deep-copies plain records when structuredClone is unavailable", () => {
        const descriptor = Object.getOwnPropertyDescriptor(globalThis, "structuredClone")

        try {
            Reflect.deleteProperty(globalThis, "structuredClone")

            const source: { name: string; details: { enabled: boolean; note?: undefined }; self?: unknown } = {
                name: "provider",
                details: { enabled: true, note: undefined },
            }
            source.self = source

            const copy = cloneApiData(source)
            expect(copy).not.toBe(source)
            expect(copy.details).not.toBe(source.details)
            expect(copy.details).toEqual({ enabled: true, note: undefined })
            expect(copy.self).toBe(copy)
            copy.details.enabled = false
            expect(source.details.enabled).toBe(true)
        } finally {
            Reflect.deleteProperty(globalThis, "structuredClone")
            if (descriptor) Object.defineProperty(globalThis, "structuredClone", descriptor)
        }
    })
})
