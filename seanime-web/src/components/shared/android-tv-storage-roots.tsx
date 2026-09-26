import { Button } from "@/components/ui/button"
import { __isAndroidTV__ } from "@/types/constants"
import React from "react"

type AndroidTVStorageRoot = {
    uri: string
    id: string
    name: string
    path: string
    available: boolean
    granted: boolean
}

function readRoots(): AndroidTVStorageRoot[] {
    try {
        return JSON.parse(window.AndroidTV?.getStorageRoots() ?? "[]") as AndroidTVStorageRoot[]
    } catch {
        return []
    }
}

export function AndroidTVStorageRoots() {
    const [roots, setRoots] = React.useState<AndroidTVStorageRoot[]>([])

    React.useEffect(() => {
        if (!__isAndroidTV__ || !window.AndroidTV) return
        setRoots(readRoots())
        const refresh = () => setRoots(readRoots())
        window.addEventListener("seanime-androidtv-storage", refresh)
        return () => window.removeEventListener("seanime-androidtv-storage", refresh)
    }, [])

    if (!__isAndroidTV__ || typeof window === "undefined" || !window.AndroidTV?.getStorageRoots) return null

    return (
        <div className="space-y-2">
            <h3 className="text-sm font-semibold">Android storage access</h3>
            {roots.length === 0 && <p className="text-sm text-[--muted]">No removable folders selected yet.</p>}
            {roots.map(root => (
                <div key={root.uri} className="flex items-center gap-3 rounded-md border border-[--border] p-3">
                    <div className="min-w-0 flex-1">
                        <p className="truncate font-medium">{root.name}</p>
                        <p className="truncate text-xs text-[--muted]">{root.path}</p>
                        <p role="status" className="text-xs text-[--muted]">
                            {root.available && root.granted ? "Connected and authorized" : "Disconnected or permission revoked"}
                        </p>
                    </div>
                    <Button
                        type="button"
                        intent="gray-outline"
                        size="sm"
                        onClick={() => {
                            window.AndroidTV?.removeStorageFolder(root.uri)
                            setRoots(readRoots())
                        }}
                    >
                        Remove access
                    </Button>
                </div>
            ))}
        </div>
    )
}
