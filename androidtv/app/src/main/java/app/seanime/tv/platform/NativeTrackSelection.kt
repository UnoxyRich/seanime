package app.seanime.tv.platform

/** Original subtitle numbers and Media3 caption ordinals are distinct wire identities. */
object NativeTrackSelection {
    fun resolve(groups: List<List<Int?>>, value: Int, matchTrackNumber: Boolean): Pair<Int, Int>? {
        if (value < 0) return null
        if (matchTrackNumber) for ((groupIndex, tracks) in groups.withIndex()) {
            val trackIndex = tracks.indexOf(value)
            if (trackIndex >= 0) return groupIndex to trackIndex
        }
        var offset = 0
        for ((groupIndex, tracks) in groups.withIndex()) {
            if (value < offset + tracks.size) return groupIndex to value - offset
            offset += tracks.size
        }
        return null
    }
}
