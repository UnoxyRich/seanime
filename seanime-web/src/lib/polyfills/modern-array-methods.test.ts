import { describe, expect, it } from "vitest"

import { installModernArrayMethods, installModernPromiseMethods } from "./modern-array-methods"

describe("modern array methods on legacy WebView", () => {
    it("provides missing methods and keeps non-mutating operations non-mutating", () => {
        const methods = ["at", "findLast", "findLastIndex", "toSorted", "toReversed", "toSpliced"] as const
        const originals = new Map(methods.map(name => [name, Object.getOwnPropertyDescriptor(Array.prototype, name)]))

        try {
            for (const name of methods) Reflect.deleteProperty(Array.prototype, name)
            installModernArrayMethods()

            const source = [3, 1, 2]
            expect(source.at(-1)).toBe(2)
            expect(source.findLast(value => value % 2 === 1)).toBe(1)
            expect(source.findLastIndex(value => value % 2 === 1)).toBe(1)
            expect(source.toSorted((a, b) => a - b)).toEqual([1, 2, 3])
            expect(source.toReversed()).toEqual([2, 1, 3])
            expect(source.toSpliced(1, 1, 4)).toEqual([3, 4, 2])
            expect(source.toSpliced(1)).toEqual([3])
            expect(source.toSpliced(1, undefined)).toEqual([3, 1, 2])
            expect(Reflect.apply(Array.prototype.toSpliced, source, [])).toEqual([])
            expect(source).toEqual([3, 1, 2])
        } finally {
            for (const name of methods) {
                Reflect.deleteProperty(Array.prototype, name)
                const descriptor = originals.get(name)
                if (descriptor) Object.defineProperty(Array.prototype, name, descriptor)
            }
            installModernArrayMethods()
        }
    })

    it("provides Promise.withResolvers when the WebView does not", async () => {
        const descriptor = Object.getOwnPropertyDescriptor(Promise, "withResolvers")

        try {
            Reflect.deleteProperty(Promise, "withResolvers")
            installModernPromiseMethods()

            const deferred = Promise.withResolvers<number>()
            deferred.resolve(42)
            await expect(deferred.promise).resolves.toBe(42)
        } finally {
            Reflect.deleteProperty(Promise, "withResolvers")
            if (descriptor) Object.defineProperty(Promise, "withResolvers", descriptor)
            else installModernPromiseMethods()
        }
    })
})
