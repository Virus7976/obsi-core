package app.obsi.core.speech

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Where kept audio lives on the phone. Lets an app delete it without starting the player. */
object KeptAudio {
    fun dir(context: Context, docId: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(docId.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "listen/kept/" + h.take(16))
    }

    fun delete(context: Context, docId: String) {
        dir(context, docId).deleteRecursively()
        ListenPrefs(context).queue(docId, false)
    }
}
