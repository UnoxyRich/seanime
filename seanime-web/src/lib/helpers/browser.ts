import { __isAndroidTV__, __isElectronDesktop__ } from "@/types/constants"
import copy from "copy-to-clipboard"


export function openTab(url: string, target: "_blank" | "_self" = "_blank") {
    if (__isAndroidTV__ && window.AndroidTV) {
        window.AndroidTV.openExternalUrl(url)
        return
    }
    window.open(url, target)
}

export async function copyToClipboard(text: string) {
    if (__isElectronDesktop__ && window.electron?.clipboard) {
        await window.electron.clipboard.writeText(text)
    } else {
        copy(text)
    }
}
