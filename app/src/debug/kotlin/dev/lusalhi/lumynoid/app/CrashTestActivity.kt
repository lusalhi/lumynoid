package dev.lusalhi.lumynoid.app

import android.app.Activity
import android.os.Bundle

/** Debug builds only. Throws on open, with a message that looks like a secret, to prove the report leaves it out. */
class CrashTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        throw IllegalStateException("Crash test. key=sk-test-not-a-real-key prompt=hello secret world")
    }
}
