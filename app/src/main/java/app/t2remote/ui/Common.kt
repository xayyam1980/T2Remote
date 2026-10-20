package app.t2remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.t2remote.hid.HidController

/** Thin status strip shown at the top of control screens. */
@Composable
fun StatusStrip() {
    val st by HidController.state.collectAsState()
    val connected = st.status == HidController.Status.CONNECTED
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            Modifier.size(10.dp).clip(CircleShape)
                .background(if (connected) Color(0xFF4CD964) else Color(0xFFFF5F57))
        )
        Spacer(Modifier.size(8.dp))
        Text(
            if (connected) st.host?.name ?: "Connected" else "Not connected — open Connect tab",
            fontSize = 13.sp, modifier = Modifier.weight(1f),
        )
        Text(st.layout.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

@Composable
fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
