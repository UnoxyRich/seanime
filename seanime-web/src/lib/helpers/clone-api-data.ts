/** Clone the plain API data objects used by settings and editable tables. */
export function cloneApiData<T>(value: T): T {
    if (typeof structuredClone === "function") return structuredClone(value)
    return cloneLegacyValue(value, new Map())
}

function cloneLegacyValue<T>(value: T, seen: Map<object, unknown>): T {
    if (value === null || typeof value !== "object") {
        if (typeof value === "function" || typeof value === "symbol") {
            throw new TypeError("Cannot clone functions or symbols in this WebView")
        }
        return value
    }

    const previous = seen.get(value)
    if (previous !== undefined) return previous as T

    if (value instanceof Date) return new Date(value.getTime()) as T
    if (value instanceof RegExp) {
        const result = new RegExp(value.source, value.flags)
        result.lastIndex = value.lastIndex
        return result as T
    }

    if (Array.isArray(value)) {
        const result: unknown[] = new Array(value.length)
        seen.set(value, result)
        for (let index = 0; index < value.length; index++) {
            result[index] = cloneLegacyValue(value[index], seen)
        }
        return result as T
    }

    if (value instanceof Map) {
        const result = new Map<unknown, unknown>()
        seen.set(value, result)
        for (const [key, entry] of value) {
            result.set(cloneLegacyValue(key, seen), cloneLegacyValue(entry, seen))
        }
        return result as T
    }

    if (value instanceof Set) {
        const result = new Set<unknown>()
        seen.set(value, result)
        for (const entry of value) result.add(cloneLegacyValue(entry, seen))
        return result as T
    }

    const prototype = Object.getPrototypeOf(value)
    if (prototype !== Object.prototype && prototype !== null) {
        throw new TypeError("Expected plain API data in this WebView")
    }

    const result = prototype === null ? Object.create(null) : {}
    seen.set(value, result)
    for (const key of Object.keys(value)) {
        Object.defineProperty(result, key, {
            configurable: true,
            enumerable: true,
            writable: true,
            value: cloneLegacyValue((value as Record<string, unknown>)[key], seen),
        })
    }
    return result as T
}
