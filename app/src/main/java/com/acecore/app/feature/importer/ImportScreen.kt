package com.acecore.app.feature.importer

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.acecore.app.container
import com.acecore.app.core.data.GameRepository
import com.acecore.app.core.data.ImportReport
import com.acecore.app.core.data.ScanCandidate
import com.acecore.app.core.data.ScanOutcome
import com.acecore.app.core.designsystem.AceCard
import com.acecore.app.core.designsystem.EmptyState
import com.acecore.app.core.designsystem.StatusChip
import com.acecore.app.core.detection.LaunchCandidate
import com.acecore.app.core.detection.Severity
import com.acecore.app.runtime.RuntimeRegistry
import kotlinx.coroutines.launch

class ImportViewModel(private val repo: GameRepository) : ViewModel() {
    sealed interface UiState {
        data class Scanning(val text: String) : UiState
        data class Results(val outcome: ScanOutcome) : UiState
        data object Importing : UiState
        data class Done(val report: ImportReport) : UiState
        data class Failed(val message: String) : UiState
    }

    var state by mutableStateOf<UiState>(UiState.Scanning("Preparing…"))
        private set
    val selected = mutableStateMapOf<String, Boolean>()
    val chosenLaunch = mutableStateMapOf<String, LaunchCandidate>()
    private var started: Uri? = null

    fun start(uri: Uri) {
        if (started == uri) return
        started = uri
        viewModelScope.launch {
            state = runCatching {
                val outcome = repo.scan(uri) { msg -> state = UiState.Scanning(msg) }
                if (outcome is ScanOutcome.Collection) outcome.candidates.forEach { selected[it.rootDocId] = true }
                if (outcome is ScanOutcome.Single) {
                    outcome.candidate.detection.launchCandidates.firstOrNull { it.playable }?.let { chosenLaunch[outcome.candidate.rootDocId] = it }
                }
                UiState.Results(outcome) as UiState
            }.getOrElse { e ->
                UiState.Failed(if (e is SecurityException) "AceCore wasn't given access to that folder." else "Scanning failed: ${e.message}")
            }
        }
    }

    fun importSelected(candidates: List<ScanCandidate>) {
        state = UiState.Importing
        viewModelScope.launch {
            state = runCatching { UiState.Done(repo.importGames(candidates, chosenLaunch.toMap())) as UiState }
                .getOrElse { UiState.Failed("Import failed: ${it.message}") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(treeUri: Uri?, onDone: () -> Unit, onOpenGame: (Long) -> Unit) {
    val ctx = LocalContext.current
    val vm: ImportViewModel = viewModel { ImportViewModel(ctx.container.games) }
    LaunchedEffect(treeUri) { treeUri?.let { vm.start(it) } }
    val state = vm.state

    LaunchedEffect(state) {
        // Single successful import goes straight to the game's page.
        if (state is ImportViewModel.UiState.Done && state.report.added.size == 1 && state.report.duplicates.isEmpty()) onOpenGame(state.report.added.first())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add games") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                is ImportViewModel.UiState.Scanning -> Progress(state.text)
                ImportViewModel.UiState.Importing -> Progress("Adding to your library…")
                is ImportViewModel.UiState.Failed -> EmptyState(Icons.Rounded.FolderOff, "Something went wrong", state.message) {
                    Button(onClick = onDone) { Text("Back") }
                }
                is ImportViewModel.UiState.Done -> DoneView(state.report, onDone)
                is ImportViewModel.UiState.Results -> when (val o = state.outcome) {
                    is ScanOutcome.NothingFound -> EmptyState(Icons.Rounded.FolderOff, "No games found", o.reason + "\n\nPick the folder that directly contains the game's files (for example the one with Game.exe or index.html), or a folder of game folders.") {
                        Button(onClick = onDone) { Text("Back") }
                    }
                    is ScanOutcome.Single -> SingleResult(vm, o.candidate)
                    is ScanOutcome.Collection -> CollectionResult(vm, o.candidates)
                }
            }
        }
    }
}

@Composable
private fun Progress(text: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SingleResult(vm: ImportViewModel, c: ScanCandidate) {
    val d = c.detection
    val runtime = RuntimeRegistry.info(d.engine)
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            AceCard(glow = true) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(d.title ?: c.folderName, style = MaterialTheme.typography.headlineSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${d.engine.displayName}${d.engineVersion?.let { " $it" } ?: ""}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        StatusChip(d.status)
                    }
                    LinearProgressIndicator(progress = { d.confidence / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("Detection confidence ${d.confidence}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(runtime.status, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (d.diagnostics.isNotEmpty()) item {
            AceCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Notes", style = MaterialTheme.typography.titleMedium)
                    d.diagnostics.forEach { Text("${severityMark(it.severity)}  ${it.message}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (d.launchCandidates.isNotEmpty()) {
            item { Text("Launch file", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
            items(d.launchCandidates, key = { it.path }) { lc ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = lc.playable) { vm.chosenLaunch[c.rootDocId] = lc }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = vm.chosenLaunch[c.rootDocId] == lc, onClick = if (lc.playable) ({ vm.chosenLaunch[c.rootDocId] = lc }) else null, enabled = lc.playable)
                    Column(Modifier.weight(1f)) {
                        Text(lc.path, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(lc.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Text("This choice is remembered. You can change it later from the game's menu.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        item {
            Button(onClick = { vm.importSelected(listOf(c)) }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 8.dp)) {
                Text(if (runtime.bundled) "Add to library" else "Add to library (not playable yet)")
            }
        }
    }
}

@Composable
private fun CollectionResult(vm: ImportViewModel, list: List<ScanCandidate>) {
    val count = list.count { vm.selected[it.rootDocId] == true }
    Column(Modifier.fillMaxSize()) {
        Text(
            "Found ${list.size} game folder(s). Untick any you don't want.",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp),
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
            items(list, key = { it.rootDocId }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.selected[c.rootDocId] = vm.selected[c.rootDocId] != true }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = vm.selected[c.rootDocId] == true, onCheckedChange = { vm.selected[c.rootDocId] = it })
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.detection.title ?: c.folderName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${c.detection.engine.displayName} · ${c.folderName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    StatusChip(c.detection.status)
                }
            }
        }
        Button(
            enabled = count > 0,
            onClick = { vm.importSelected(list.filter { vm.selected[it.rootDocId] == true }) },
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
        ) { Text("Add $count game(s)") }
    }
}

@Composable
private fun DoneView(report: ImportReport, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Added ${report.added.size} game(s)", style = MaterialTheme.typography.headlineSmall)
        if (report.duplicates.isNotEmpty()) Text("Already in your library: ${report.duplicates.joinToString()}", style = MaterialTheme.typography.bodyMedium)
        if (report.failed.isNotEmpty()) Text("Failed: ${report.failed.joinToString("\n")}", color = MaterialTheme.colorScheme.error)
        Button(onClick = onDone) { Text("Back to library") }
    }
}

fun severityMark(s: Severity) = when (s) { Severity.INFO -> "ℹ"; Severity.WARNING -> "⚠"; Severity.ERROR -> "✖" }
