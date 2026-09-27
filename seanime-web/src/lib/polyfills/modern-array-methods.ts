type CompareFn<T> = (left: T, right: T) => number
type Predicate<T> = (value: T, index: number, array: readonly T[]) => unknown

/** Add modern Object methods missing from older Android System WebView builds. */
export function installModernObjectMethods() {
    if (typeof Object.hasOwn !== "function") {
        Object.defineProperty(Object, "hasOwn", {
            configurable: true,
            writable: true,
            value: function (object: unknown, propertyKey: PropertyKey): boolean {
                return Object.prototype.hasOwnProperty.call(object, propertyKey)
            },
        })
    }
}

/** Add modern array methods missing from older Android System WebView builds. */
export function installModernArrayMethods() {
    if (typeof Array.prototype.at !== "function") {
        Object.defineProperty(Array.prototype, "at", {
            configurable: true,
            writable: true,
            value: function <T>(this: readonly T[], index: number): T | undefined {
                const numericIndex = Number(index)
                const relativeIndex = Number.isNaN(numericIndex) || numericIndex === 0
                    ? 0
                    : Math.trunc(numericIndex)
                const actualIndex = relativeIndex < 0 ? this.length + relativeIndex : relativeIndex
                return actualIndex >= 0 && actualIndex < this.length ? this[actualIndex] : undefined
            },
        })
    }

    if (typeof Array.prototype.findLast !== "function") {
        Object.defineProperty(Array.prototype, "findLast", {
            configurable: true,
            writable: true,
            value: function <T>(this: readonly T[], predicate: Predicate<T>, thisArg?: unknown): T | undefined {
                if (typeof predicate !== "function") throw new TypeError("predicate must be a function")
                for (let index = this.length - 1; index >= 0; index--) {
                    const value = this[index]
                    if (predicate.call(thisArg, value, index, this)) return value
                }
                return undefined
            },
        })
    }

    if (typeof Array.prototype.findLastIndex !== "function") {
        Object.defineProperty(Array.prototype, "findLastIndex", {
            configurable: true,
            writable: true,
            value: function <T>(this: readonly T[], predicate: Predicate<T>, thisArg?: unknown): number {
                if (typeof predicate !== "function") throw new TypeError("predicate must be a function")
                for (let index = this.length - 1; index >= 0; index--) {
                    if (predicate.call(thisArg, this[index], index, this)) return index
                }
                return -1
            },
        })
    }

    if (typeof Array.prototype.toSorted !== "function") {
        Object.defineProperty(Array.prototype, "toSorted", {
            configurable: true,
            writable: true,
            value: function <T>(this: readonly T[], compareFn?: CompareFn<T>): T[] {
                return Array.from(this).sort(compareFn)
            },
        })
    }

    if (typeof Array.prototype.toReversed !== "function") {
        Object.defineProperty(Array.prototype, "toReversed", {
            configurable: true,
            writable: true,
            value: function <T>(this: readonly T[]): T[] {
                return Array.from(this).reverse()
            },
        })
    }

    if (typeof Array.prototype.toSpliced !== "function") {
        Object.defineProperty(Array.prototype, "toSpliced", {
            configurable: true,
            writable: true,
            value: function <T>(this: readonly T[], start: number, deleteCount?: number, ...items: T[]): T[] {
                const copy = Array.from(this)
                if (arguments.length <= 1) copy.splice(start)
                else copy.splice(start, deleteCount === undefined ? 0 : deleteCount, ...items)
                return copy
            },
        })
    }
}

/** Add modern Promise helpers missing from older Android System WebView builds. */
export function installModernPromiseMethods() {
    if (typeof Promise.withResolvers !== "function") {
        Object.defineProperty(Promise, "withResolvers", {
            configurable: true,
            writable: true,
            value: function <T>() {
                let resolve!: (value: T | PromiseLike<T>) => void
                let reject!: (reason?: unknown) => void
                const promise = new Promise<T>((resolvePromise, rejectPromise) => {
                    resolve = resolvePromise
                    reject = rejectPromise
                })
                return { promise, resolve, reject }
            },
        })
    }
}

installModernArrayMethods()
installModernPromiseMethods()
installModernObjectMethods()
