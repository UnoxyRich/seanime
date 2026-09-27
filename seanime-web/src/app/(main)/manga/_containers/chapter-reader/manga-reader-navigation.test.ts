import { describe, expect, it } from "vitest"
import { MangaReadingMode } from "@/app/(main)/manga/_lib/manga-chapter-reader.atoms"
import { getAdjacentMangaPageIndex } from "./manga-reader-navigation"

const paginationMap = { 0: [0, 1], 1: [2], 2: [3, 4] }

describe("manga reader page navigation", () => {
    it("moves one page in single-page and long-strip modes", () => {
        for (const readingMode of [MangaReadingMode.PAGED, MangaReadingMode.LONG_STRIP]) {
            expect(getAdjacentMangaPageIndex({
                direction: "next", readingMode, currentPageIndex: 1, currentMapIndex: 1,
                pageCount: 5, paginationMap,
            })).toBe(2)
            expect(getAdjacentMangaPageIndex({
                direction: "previous", readingMode, currentPageIndex: 1, currentMapIndex: 1,
                pageCount: 5, paginationMap,
            })).toBe(0)
        }
    })

    it("moves by the next or previous double-page spread", () => {
        expect(getAdjacentMangaPageIndex({
            direction: "next", readingMode: MangaReadingMode.DOUBLE_PAGE, currentPageIndex: 0,
            currentMapIndex: 0, pageCount: 5, paginationMap,
        })).toBe(2)
        expect(getAdjacentMangaPageIndex({
            direction: "previous", readingMode: MangaReadingMode.DOUBLE_PAGE, currentPageIndex: 3,
            currentMapIndex: 2, pageCount: 5, paginationMap,
        })).toBe(2)
    })

    it("returns no adjacent page at either end", () => {
        expect(getAdjacentMangaPageIndex({
            direction: "previous", readingMode: MangaReadingMode.PAGED, currentPageIndex: 0,
            currentMapIndex: 0, pageCount: 5, paginationMap,
        })).toBeUndefined()
        expect(getAdjacentMangaPageIndex({
            direction: "next", readingMode: MangaReadingMode.PAGED, currentPageIndex: 4,
            currentMapIndex: 4, pageCount: 5, paginationMap,
        })).toBeUndefined()
        expect(getAdjacentMangaPageIndex({
            direction: "next", readingMode: MangaReadingMode.DOUBLE_PAGE, currentPageIndex: 3,
            currentMapIndex: 2, pageCount: 5, paginationMap,
        })).toBeUndefined()
    })
})
