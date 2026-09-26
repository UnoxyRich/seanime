import { __isAndroidTV__, __isElectronDesktop__ } from "@/types/constants"
import copy from "copy-to-clipboard"


export function openTab(url: string, target: "_blank" | "_self" = "_blank") {
    if (__isAndroidTV__ && window.AndroidTV) {
        window.AndroidTV.openExternalUrl(url)
        return
    }
    window.open(url, target)
}

export async function downloadBlobAs(blob: Blob, filename: string): Promise<boolean> {
    if (__isAndroidTV__ && window.AndroidTV) {
        const bridge = window.AndroidTV
        const requestId = window.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`
        const result = await new Promise<{ ready: boolean, error?: string }>((resolve, reject) => {
            const cleanup = () => {
                window.clearTimeout(timeout)
                window.removeEventListener("seanime-androidtv-download-target", onTarget)
            }
            const onTarget = (event: Event) => {
                const detail = (event as CustomEvent<{ requestId: string, ready: boolean, error?: string }>).detail
                if (detail?.requestId !== requestId) return
                cleanup()
                resolve({ ready: detail.ready, error: detail.error })
            }
            const timeout = window.setTimeout(() => {
                cleanup()
                reject(new Error("Timed out waiting for Android's save location"))
            }, 180_000)

            window.addEventListener("seanime-androidtv-download-target", onTarget)
            if (!bridge.requestDownloadTarget(requestId, filename, blob.type || "application/octet-stream")) {
                cleanup()
                reject(new Error("Android could not open a save location"))
            }
        })
        if (!result.ready) {
            if (result.error && result.error !== "Download canceled") throw new Error(result.error)
            return false
        }

        try {
            const chunkSize = 256 * 1024
            for (let offset = 0; offset < blob.size; offset += chunkSize) {
                const bytes = new Uint8Array(await blob.slice(offset, offset + chunkSize).arrayBuffer())
                let binary = ""
                for (let index = 0; index < bytes.length; index += 0x8000) {
                    binary += String.fromCharCode(...bytes.subarray(index, Math.min(index + 0x8000, bytes.length)))
                }
                if (!bridge.writeDownloadChunk(requestId, btoa(binary))) {
                    throw new Error("Android could not write the downloaded file")
                }
            }
            if (!bridge.finishDownload(requestId)) {
                throw new Error("Android could not finish saving the file")
            }
            return true
        } catch (error) {
            bridge.cancelDownload(requestId)
            throw error
        }
    }

    const url = URL.createObjectURL(blob)
    const link = document.createElement("a")
    link.href = url
    link.download = filename
    link.style.display = "none"
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 1_000)
    return true
}

export async function copyToClipboard(text: string) {
    if (__isElectronDesktop__ && window.electron?.clipboard) {
        await window.electron.clipboard.writeText(text)
    } else {
        copy(text)
    }
}
