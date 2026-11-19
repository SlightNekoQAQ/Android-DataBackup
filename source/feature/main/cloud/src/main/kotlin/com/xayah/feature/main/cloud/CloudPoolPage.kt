package com.xayah.feature.main.cloud

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.xayah.core.ui.token.SizeTokens

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PageCloudPool() {
    val model = hiltViewModel<IndexViewModel>()
    CloudScaffold(
        scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState()),
        snackbarHostState = model.snackbarHostState,
        title = stringResource(R.string.yunx_pool_page),
        actions = {},
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(SizeTokens.Level24)) {
            YunxImport()
            CloudSnapshotActions()
        }
    }
}
