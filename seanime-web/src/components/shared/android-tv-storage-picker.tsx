import { Button } from "@/components/ui/button"
import { __isAndroidTV__ } from "@/types/constants"
import React from "react"

type AndroidTVStorageRoot = {
    id: string
    name: string
    path: string
    available: boolean
    granted: boolean
}

type AndroidTVStorageEvent = {
    purpose: string
    root: AndroidTVStorageRoot | null
}

type AndroidTVStoragePickerProps = {
    label: string
    purpose: "library-main" | "library-additional" | "manga-local" | "torrent-stream"
    onSelect: (root: AndroidTVStorageRoot) => void
}

export function AndroidTVStoragePicker({ label, purpose, onSelect }: AndroidTVStoragePickerProps) {
    const [pending, setPending] = React.useState(false)
    const [message, setMessage] = React.useState("")

    React.useEffect(() => {
        const handleSelection = (event: Event) => {
            const selection = (event as CustomEvent<AndroidTVStorageEvent>).detail
            if (selection.purpose !== purpose) return
            setPending(false)
            const root = selection.root
            if (!root) return
            if (!root.available || !root.granted || !root.path) {
                setMessage("Seanime could not keep access to that folder. Select it again.")
                return
            }
            setMessage(`Selected ${root.name}`)
            onSelect(root)
        }
        window.addEventListener("seanime-androidtv-storage", handleSelection)
        return () => window.removeEventListener("seanime-androidtv-storage", handleSelection)
    }, [onSelect, purpose])

    if (!__isAndroidTV__ || typeof window === "undefined" || !window.AndroidTV?.requestMediaFolder) return null

    return (
        <div className="space-y-2">
            <Button
                type="button"
                intent="gray-outline"
                size="sm"
                disabled={pending}
                onClick={() => {
                    setMessage("")
                    setPending(true)
                    window.AndroidTV?.requestMediaFolder(purpose)
                }}
            >
                {pending ? "Waiting for folder selection…" : label}
            </Button>
            {!!message && <p role="status" className="text-sm text-[--muted]">{message}</p>}
        </div>
    )
}
