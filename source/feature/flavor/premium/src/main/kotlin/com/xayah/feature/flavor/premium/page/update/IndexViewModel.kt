package com.xayah.feature.flavor.premium.page.update

import androidx.compose.material3.ExperimentalMaterial3Api
import com.xayah.core.common.viewmodel.BaseViewModel
import com.xayah.core.common.viewmodel.IndexUiEffect
import com.xayah.core.common.viewmodel.UiIntent
import com.xayah.core.common.viewmodel.UiState
import com.xayah.core.network.model.Release
import com.xayah.core.network.retrofit.GitHubNetwork
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class IndexUiState(
    val isInitializing: Boolean = true,
) : UiState

sealed class IndexUiIntent : UiIntent {
    object Initialize : IndexUiIntent()
}

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    private val gitHubNetwork: GitHubNetwork,
) : BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(IndexUiState()) {
    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        when (intent) {
            is IndexUiIntent.Initialize -> {
                runCatching {
                    _releases.value = gitHubNetwork.getReleases()
                }.onFailure {
                    val msg = it.message
                    if (msg != null)
                        emitEffect(IndexUiEffect.ShowSnackbar(message = msg))
                }
                emitState(state = state.copy(isInitializing = false))
            }
        }
    }

    private val _releases: MutableStateFlow<List<Release>> = MutableStateFlow(listOf())
    val releases: StateFlow<List<Release>> = _releases.asStateFlow()
}
