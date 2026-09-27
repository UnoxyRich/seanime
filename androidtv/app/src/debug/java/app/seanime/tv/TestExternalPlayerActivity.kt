package app.seanime.tv

import android.app.Activity
import android.os.Bundle

internal object TestExternalPlayerIntent {
    @Volatile
    var action: String? = null

    @Volatile
    var uri: String? = null

    fun reset() {
        action = null
        uri = null
    }
}

class TestExternalPlayerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TestExternalPlayerIntent.action = intent?.action
        TestExternalPlayerIntent.uri = intent?.dataString
        finish()
    }
}
