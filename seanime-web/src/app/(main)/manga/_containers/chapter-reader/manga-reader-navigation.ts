import { MangaReadingMode } from "@/app/(main)/manga/_lib/manga-chapter-reader.atoms"

type MangaReaderPageNavigation = {
    direction: "previous" | "next"
    readingMode: string
    currentPageIndex: number
    currentMapIndex: number
    pageCount: number
    paginationMap: Record<number, number[]>
}

export function getAdjacentMangaPageIndex({
    direction,
    readingMode,
    currentPageIndex,
    currentMapIndex,
    pageCount,
    paginationMap,
}: MangaReaderPageNavigation): number | undefined {
    if (readingMode === MangaReadingMode.DOUBLE_PAGE) {
        return paginationMap[currentMapIndex + (direction === "next" ? 1 : -1)]?.[0]
    }

    const candidate = currentPageIndex + (direction === "next" ? 1 : -1)
    return candidate >= 0 && candidate < pageCount ? candidate : undefined
}
