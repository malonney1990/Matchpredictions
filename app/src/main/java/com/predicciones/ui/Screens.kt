package com.predicciones.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.predicciones.data.Fixture
import com.predicciones.engine.PickFormatter

@Composable
fun AppRoot(vm: AlmanaqueViewModel = viewModel()) {
    val detail by vm.detail.collectAsState()
    val current = detail
    if (current != null) {
        BackHandler(onBack = { vm.close() })
        DetailScreen(current, onBack = { vm.close() })
    } else {
        AlmanaqueScreen(vm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlmanaqueScreen(vm: AlmanaqueViewModel) {
    val date by vm.date.collectAsState()
    val fixtures by vm.fixtures.collectAsState()
    val grouped = remember(fixtures) { fixtures.groupBy { it.leagueName } }

    Scaffold(topBar = { TopAppBar(title = { Text("Almanaque de partidos") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { vm.shiftDays(-1) }) { Text("◀") }
                Text(date.toString(), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { vm.shiftDays(1) }) { Text("▶") }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { vm.today() }) { Text("Hoy") }
            }
            Text(
                "Datos de ejemplo: todavía no hay API conectada.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (fixtures.isEmpty()) {
                Text("No hay partidos este día.", modifier = Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                grouped.forEach { (league, list) ->
                    item(key = "h-$league") {
                        Text(
                            league,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(list, key = { it.id }) { f -> FixtureRow(f) { vm.open(f) } }
                }
            }
        }
    }
}

@Composable
private fun FixtureRow(f: Fixture, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("${f.timeGmt} GMT", modifier = Modifier.width(88.dp), style = MaterialTheme.typography.bodyMedium)
        Text("${f.homeName} – ${f.awayName}", style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(state: DetailState, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Picks del partido") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            val f = state.fixture
            Text(f.leagueName, style = MaterialTheme.typography.labelMedium)
            Text(
                PickFormatter.header(f.homeName, f.awayName, f.dateIso, f.timeGmt),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            when (state) {
                is DetailState.Loading -> CircularProgressIndicator()
                is DetailState.Error -> Text(
                    "No se pudo calcular: ${state.message}",
                    color = MaterialTheme.colorScheme.error,
                )
                is DetailState.Ready -> ReadyContent(state)
            }
        }
    }
}

@Composable
private fun ReadyContent(state: DetailState.Ready) {
    val p = state.prediction
    if (p.picks.isEmpty()) {
        Text("No hay cuotas disponibles para este partido.")
        return
    }
    p.picks.forEachIndexed { i, pick ->
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(PickFormatter.pickLine(i + 1, pick), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    PickFormatter.justification(pick, p.expected, p.runs, p.seed, p.h2hEfectivos),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    p.advertencias.forEach {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
