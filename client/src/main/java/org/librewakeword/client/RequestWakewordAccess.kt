package org.librewakeword.client

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/** Activity-result contract that asks the user to approve one declared consumer service. */
class RequestWakewordAccess : ActivityResultContract<ComponentName, Boolean>() {
    override fun createIntent(context: Context, input: ComponentName): Intent = Intent(
        WakewordContract.ACTION_REQUEST_ACCESS,
    ).apply {
        component = ComponentName(
            WakewordContract.LIBREWAKEWORD_PACKAGE,
            "${WakewordContract.LIBREWAKEWORD_PACKAGE}.AccessRequestActivity",
        )
        putExtra(EXTRA_CONSUMER, input)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        resultCode == Activity.RESULT_OK

    companion object {
        const val EXTRA_CONSUMER = "org.librewakeword.extra.CONSUMER"
    }
}
