import type { KeyboardEvent } from "react"

/** Keyboard activation for clickable containers whose layout or refs require a non-button element. */
export function keyboardClickable(enabled = true) {
    if (!enabled) return {}
    return {
        role: "button" as const,
        tabIndex: 0,
        onKeyDown(event: KeyboardEvent<HTMLElement>) {
            if (event.target !== event.currentTarget || event.defaultPrevented || event.repeat) return
            if (event.key !== "Enter" && event.key !== " ") return
            if (event.currentTarget.getAttribute("aria-disabled") === "true") return
            event.preventDefault()
            event.stopPropagation()
            event.currentTarget.click()
        },
    }
}
