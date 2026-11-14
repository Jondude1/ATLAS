package com.jondude.anzu.overlay

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One-screen explainer for "Display over other apps"; starts the companion the moment it's granted. */
class OverlaySetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.canDrawOverlays(this)) {
            launchAndFinish()
            return
        }
        setContent {
            Explainer(
                onGrant = {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:com.jondude.anzu")))
                },
                onDismiss = { finish() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) launchAndFinish()
    }

    private fun launchAndFinish() {
        OverlayService.start(this)
        finish()
    }
}

private val Accent = Color(0xFFFF5C00)

@Composable
private fun Explainer(onGrant: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0B0B0B)).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Atlas companion", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "A small floating orb that keeps Atlas with you on top of other apps. It shows when Atlas is listening, " +
                "thinking or speaking. Tap it to talk, long-press it for a quick status panel, drag it to any screen edge.",
            color = Color(0xFFBBBBBB), fontSize = 15.sp, modifier = Modifier.padding(vertical = 20.dp)
        )
        Text(
            "Android needs your OK once: allow “Display over other apps” for Atlas. The orb appears as soon as you do.",
            color = Color(0xFF888888), fontSize = 13.sp, modifier = Modifier.padding(bottom = 24.dp)
        )
        Button(
            onClick = onGrant,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
        ) { Text("Grant display over other apps") }
        TextButton(onClick = onDismiss) { Text("Not now", color = Color(0xFFAAAAAA)) }
    }
}
