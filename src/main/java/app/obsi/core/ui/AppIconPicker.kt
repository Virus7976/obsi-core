package app.obsi.core.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.obsi.core.Brand

/**
 * One choice of launcher icon. Each one is an activity-alias in the app's manifest, named Icon plus [id].
 * [art] is a full rounded preview, so it fills the tile edge to edge.
 */
class AppIcon(val id: String, val label: String, val art: Int, val bg: Color = Color.Transparent)

private fun component(ctx: Context, id: String) = ComponentName(ctx.packageName, "${Brand.app.pkg}.Icon$id")

/** The system holds this, so what shows here is always what the phone shows. */
fun currentAppIcon(ctx: Context, icons: List<AppIcon>): String = icons.firstOrNull {
    runCatching { ctx.packageManager.getComponentEnabledSetting(component(ctx, it.id)) }.getOrNull() ==
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
}?.id ?: icons.first().id

fun setAppIcon(ctx: Context, icons: List<AppIcon>, id: String, hidden: List<String> = emptyList()) {
    val pm = ctx.packageManager
    fun set(i: String, state: Int) = runCatching { pm.setComponentEnabledSetting(component(ctx, i), state, PackageManager.DONT_KILL_APP) }
    // The new one goes on first, so the app is never left with no icon at all.
    set(id, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
    (icons.map { it.id } + hidden).filter { it != id }.forEach { set(it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED) }
}

/**
 * The app icon row in Settings, the same in every Obsi app. [hidden] lists old icon choices that are no
 * longer shown, so picking a new one turns them off too.
 */
@Composable
fun AppIconPicker(icons: List<AppIcon>, hidden: List<String> = emptyList()) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var picked by remember { mutableStateOf(currentAppIcon(ctx, icons)) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("App icon", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)) {
            items(icons, key = { it.id }) { icon ->
                val on = icon.id == picked
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.RadioButton, onClickLabel = icon.label) {
                        if (!on) { setAppIcon(ctx, icons, icon.id, hidden); picked = icon.id }
                    }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // A ring around the picked one, with a gap, like the system's own icon picker.
                    val ring = RoundedCornerShape(20.dp)
                    Box(
                        Modifier.size(68.dp).border(2.dp, if (on) scheme.primary else Color.Transparent, ring).padding(5.dp),
                    ) {
                        Image(
                            painterResource(icon.art), icon.label,
                            Modifier.fillMaxSize().clip(RoundedCornerShape(15.dp)).background(icon.bg),
                        )
                    }
                    Text(icon.label, style = MaterialTheme.typography.labelSmall, color = if (on) scheme.onSurface else scheme.onSurfaceVariant)
                }
            }
        }
    }
}
