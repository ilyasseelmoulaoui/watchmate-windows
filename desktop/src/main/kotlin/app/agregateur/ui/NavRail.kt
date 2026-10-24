package app.agregateur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.agregateur.Tab

/** Rail de 96 dp. L'onglet actif ne se remplit pas : un trait Néon de 3 × 20 dp le signale au bord. */
@Composable
fun NavRail(selected: Tab, french: Boolean, onSelect: (Tab) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxHeight()) {
        Column(
            Modifier.width(95.dp).fillMaxHeight().padding(vertical = 20.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Tab.entries.forEach { tab ->
                val active = tab == selected
                val tint = if (active) colors.onBackground else colors.onSurfaceVariant
                Box(Modifier.width(80.dp).height(64.dp)) {
                    if (active) {
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .offset(x = (-8).dp)
                                .width(3.dp)
                                .height(20.dp)
                                .background(colors.primary, RoundedCornerShape(2.dp)),
                        )
                    }
                    Column(
                        Modifier
                            .matchParentSize()
                            .wmClickable(RoundedCornerShape(12.dp), role = Role.Tab) { onSelect(tab) }
                            .semantics { this.selected = active },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        Icon(tab.icon, null, tint = tint, modifier = Modifier.size(24.dp))
                        Text(
                            if (french) tab.labelFr else tab.labelEn,
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.surfaceVariant))
    }
}
