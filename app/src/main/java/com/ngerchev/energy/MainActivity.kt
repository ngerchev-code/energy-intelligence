package com.ngerchev.energy

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ngerchev.energy.ui.AboutScreen
import com.ngerchev.energy.ui.BessScreen
import com.ngerchev.energy.ui.Body
import com.ngerchev.energy.ui.Display
import com.ngerchev.energy.ui.EColors
import com.ngerchev.energy.ui.EnergyTheme
import com.ngerchev.energy.ui.GridScreen
import com.ngerchev.energy.ui.Icons
import com.ngerchev.energy.ui.MarketScreen

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent { EnergyTheme { App(vm) } }
    }
}

private val tabs = listOf("Цени", "Мрежа", "BESS", "Още")

@Composable
private fun App(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val bess by vm.bess.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize().background(EColors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            Header(live = state.data?.fromCache == false, loading = state.loading, onRefresh = vm::refresh)
            Box(Modifier.weight(1f)) {
                val pad = PaddingValues(0.dp)
                val data = state.data
                when {
                    tab == 3 -> AboutScreen(data, pad)
                    data != null -> when (tab) {
                        0 -> MarketScreen(data, pad)
                        1 -> GridScreen(data, pad)
                        else -> BessScreen(data, bess, vm::updateBess, pad)
                    }
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = EColors.Lime)
                    }
                    else -> ErrorState(state.error, vm::refresh)
                }
            }
        }
        Dock(tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Header(live: Boolean, loading: Boolean, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                append("ЕНЕРГО")
                withStyle(SpanStyle(color = EColors.Lime)) { append("·") }
                append("BG")
            },
            color = EColors.Text, fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp,
            modifier = Modifier.weight(1f),
        )
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(EColors.Tile)
                .clickable(enabled = !loading, role = Role.Button, onClickLabel = "Обнови данните", onClick = onRefresh)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(12.dp), color = EColors.Lime, strokeWidth = 2.dp)
            } else {
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (live) EColors.Lime else EColors.Sand))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                when { loading -> "Зарежда"; live -> "На живо"; else -> "Офлайн" },
                color = EColors.Text, fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            )
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(18.dp)) { Icons.Refresh() }
        }
    }
}

/** Floating light pill with the four sections — the signature of this app. */
@Composable
private fun Dock(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    Row(
        modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 14.dp)
            .clip(RoundedCornerShape(50)).background(EColors.Text).padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tabs.forEachIndexed { i, t ->
            val sel = i == selected
            Text(
                t,
                color = if (sel) EColors.Text else EColors.Bg,
                fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (sel) EColors.Bg else EColors.Text)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .padding(horizontal = if (sel) 20.dp else 15.dp, vertical = 13.dp),
            )
        }
    }
}

@Composable
private fun ErrorState(error: String?, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Няма връзка", color = EColors.Text, fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        Spacer(Modifier.height(8.dp))
        Text(error ?: "Данните не можаха да се заредят.", color = EColors.Muted, fontFamily = Body, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Text(
            "Опитай отново",
            color = EColors.OnLime, fontFamily = Body, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(EColors.Lime)
                .clickable(role = Role.Button, onClick = onRetry).padding(horizontal = 24.dp, vertical = 14.dp),
        )
    }
}
