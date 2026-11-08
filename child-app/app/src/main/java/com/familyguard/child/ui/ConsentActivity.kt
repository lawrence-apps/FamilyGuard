package com.familyguard.child.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.familyguard.child.data.Store
import com.familyguard.child.service.MirrorManager
import com.familyguard.child.service.MirrorService
import com.familyguard.child.util.AgeRelease

class ConsentActivity : ComponentActivity() {

    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK && res.data != null) {
            val i = Intent(this, MirrorService::class.java)
            i.putExtra("resultCode", res.resultCode)
            i.putExtra("data", res.data)
            ContextCompat.startForegroundService(this, i)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Store.paired || Store.released || MirrorManager.hasProjection) {
            finish()
            return
        }
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        runCatching { projLauncher.launch(mgr.createScreenCaptureIntent()) }.onFailure { finish() }
    }
}
