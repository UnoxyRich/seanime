package app.seanime.tv.platform

/** Platform adapters request presentation; they never build Android View dialogs. */
data class NativePrompt(
    val title: String,
    val message: String,
    val actions: List<NativePromptAction> = emptyList(),
    val dismissLabel: String = "Close",
    val focusDismiss: Boolean = true,
)
data class NativePromptAction(val label: String, val invoke: () -> Unit)
