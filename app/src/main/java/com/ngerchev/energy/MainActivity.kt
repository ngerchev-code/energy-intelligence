package com.ngerchev.energy

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ngerchev.energy.ui.AboutScreen
import com.ngerchev.energy.ui.BessScreen
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

private data class Tab(val title: String, val label: String, val icon: @Composable () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val bess by vm.bess.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        Tab("Пазар ден напред", "Цени") { Icons.Price() },
        Tab("Енергийна система", "Мрежа") { Icons.Grid() },
        Tab("Батерии (BESS)", "BESS") { Icons.Battery() },
        Tab("За приложението", "Инфо") { Icons.Info() },
    )

    Scaffold(
        containerColor = EColors.Bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(tabs[tab].title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("Energy Intelligence Bulgaria", color = EColors.Teal, fontSize = 11.sp)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }, enabled = !state.loading) {
                        if (state.loading) CircularProgressIndicator(
                            Modifier.padding(12.dp), strokeWidth = 2.dp, color = EColors.Teal,
                        ) else Icons.Refresh()
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = EColors.Bg, titleContentColor = EColors.Text, actionIconContentColor = EColors.Text,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = EColors.Card) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = t.icon,
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = EColors.Bg,
                            selectedTextColor = EColors.Teal,
                            indicatorColor = EColors.Teal,
                            unselectedIconColor = EColors.Muted,
                            unselectedTextColor = EColors.Muted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        val data = state.data
        when {
            tab == 3 -> AboutScreen(data, padding)
            data != null -> when (tab) {
                0 -> MarketScreen(data, padding)
                1 -> GridScreen(data, padding)
                else -> BessScreen(data, bess, vm::updateBess, padding)
            }
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = EColors.Teal)
            }
            else -> Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Няма връзка", color = EColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    state.error ?: "Данните не можаха да се заредят.",
                    color = EColors.Muted, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.refresh() }) { Text("Опитай отново") }
            }
        }
    }
}
