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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import com.ngerchev.energy.ui.AnalystScreen
import com.ngerchev.energy.ui.EuropeScreen
import com.ngerchev.energy.ui.HomeScreen
import com.ngerchev.energy.ui.KnowledgeScreen
import com.ngerchev.energy.ui.MoreScreen
import com.ngerchev.energy.ui.NewsScreen
import com.ngerchev.energy.ui.Palette
import com.ngerchev.energy.ui.PvBessScreen
import com.ngerchev.energy.ui.ReportsScreen
import com.ngerchev.energy.ui.Screen
import com.ngerchev.energy.ui.SettingsScreen
import com.ngerchev.energy.ui.TradeScreen
import com.ngerchev.energy.ui.TutorialScreen
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
        setContent {
            val theme by vm.theme.collectAsStateWithLifecycle()
            val sys = isSystemInDarkTheme()
            Palette.light = when (theme) { 2 -> true; 1 -> false; else -> !sys }
            DisposableEffect(Palette.light) {
                val style = if (Palette.light) SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                else SystemBarStyle.dark(Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            EnergyTheme { App(vm) }
        }
    }
}

private val dockTabs = listOf(Screen.HOME, Screen.MARKET, Screen.EUROPE, Screen.NEWS, Screen.MORE)

@Composable
private fun App(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val bess by vm.bess.collectAsStateWithLifecycle()
    var screenName by rememberSaveable { mutableStateOf(Screen.HOME.name) }
    var backName by rememberSaveable { mutableStateOf<String?>(null) }
    val screen = Screen.valueOf(screenName)
    val go: (Screen) -> Unit = { s ->
        backName = if (s in dockTabs) null else screenName
        screenName = s.name
    }
    BackHandler(enabled = screen != Screen.HOME) {
        screenName = backName ?: Screen.HOME.name
        backName = null
    }

    Box(Modifier.fillMaxSize().background(EColors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            Header(screen, live = state.data?.fromCache == false, loading = state.loading, onRefresh = vm::refresh)
            Box(Modifier.weight(1f)) {
                val pad = PaddingValues(0.dp)
                val data = state.data
                when {
                    screen == Screen.MORE -> MoreScreen(pad, go)
                    screen == Screen.SETTINGS -> SettingsScreen(data, vm.theme.collectAsStateWithLifecycle().value, vm::setTheme, pad)
                    screen == Screen.KNOWLEDGE -> KnowledgeScreen(vm.knowledge, pad)
                    data != null -> when (screen) {
                        Screen.HOME -> HomeScreen(data, vm.knowledge, pad, go)
                        Screen.MARKET -> MarketScreen(data, pad)
                        Screen.EUROPE -> EuropeScreen(data, pad)
                        Screen.PRODUCTION -> GridScreen(data, pad)
                        Screen.TRADE -> TradeScreen(data, pad)
                        Screen.BESS -> BessScreen(data, bess, vm::updateBess, pad)
                        Screen.PVBESS -> PvBessScreen(data, vm.pvBess.collectAsStateWithLifecycle().value, { vm.pvBess.value = it },
                            vm.pvSite.collectAsStateWithLifecycle().value, { vm.pvSite.value = it }, pad)
                        Screen.NEWS -> NewsScreen(data.news, vision = false, padding = pad)
                        Screen.VISION -> NewsScreen(data.news, vision = true, padding = pad)
                        Screen.ANALYST -> AnalystScreen(data, pad)
                        Screen.REPORTS -> ReportsScreen(data, pad)
                        Screen.TUTORIAL -> TutorialScreen(data.lessons, pad)
                        else -> MoreScreen(pad, go)
                    }
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = EColors.Lime)
                    }
                    else -> ErrorState(state.error, vm::refresh)
                }
            }
        }
        Dock(dockTabs.indexOf(if (screen in dockTabs) screen else Screen.MORE), { go(dockTabs[it]) }, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Header(screen: Screen, live: Boolean, loading: Boolean, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    append("ЕНЕРГО")
                    withStyle(SpanStyle(color = EColors.Lime)) { append("·") }
                    append("BG")
                },
                color = EColors.Text, fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp,
            )
            if (screen != Screen.HOME) Text(screen.title, color = EColors.Muted, fontFamily = Body, fontSize = 12.sp)
        }
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
        dockTabs.forEachIndexed { i, t ->
            val sel = i == selected
            Text(
                t.short,
                color = if (sel) EColors.Text else EColors.Bg,
                fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (sel) EColors.Bg else EColors.Text)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .padding(horizontal = if (sel) 14.dp else 10.dp, vertical = 13.dp),
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
