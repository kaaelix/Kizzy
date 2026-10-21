/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * UserScreen.kt is part of Kizzy
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

package com.my.kizzy.feature_profile.ui.user

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.my.kizzy.domain.model.user.User
import com.my.kizzy.feature_profile.ui.component.DiscordLoginButton
import com.my.kizzy.feature_profile.ui.component.DiscordLoginWebView
import com.my.kizzy.feature_profile.ui.component.Logout
import com.my.kizzy.feature_profile.ui.component.ProfileCard
import com.my.kizzy.feature_profile.ui.component.ProfileNetworkError
import com.my.kizzy.preference.Prefs
import com.my.kizzy.resources.R
import com.my.kizzy.ui.components.BackButton
import com.my.kizzy.ui.components.shimmer.AnimatedShimmer
import com.my.kizzy.ui.components.shimmer.ShimmerProfileCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserScreen(
    viewModel: UserViewModel,
    onBackPressed: () -> Unit
) {
    val selectedAccount by viewModel.selectedAccount
    val account1State by viewModel.account1State
    val account2State by viewModel.account2State
    val account1RpcEnabled by viewModel.account1RpcEnabled
    val account2RpcEnabled by viewModel.account2RpcEnabled

    val currentState = if (selectedAccount == 1) account1State else account2State
    val isAccount1LoggedIn = Prefs.isAccountLoggedIn(1)
    val isAccount2LoggedIn = Prefs.isAccountLoggedIn(2)

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showLoginBottomSheet by remember { mutableStateOf(false) }
    var showManualTokenDialog by remember { mutableStateOf(false) }
    var manualTokenInput by remember { mutableStateOf("") }
    val modalBottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = "Akun Discord") },
                navigationIcon = { BackButton { onBackPressed() } }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Tabs: Akun 1 & Akun 2
            TabRow(
                selectedTabIndex = selectedAccount - 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
            ) {
                Tab(
                    selected = selectedAccount == 1,
                    onClick = { viewModel.selectAccount(1) },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isAccount1LoggedIn) Color(0xFF4CAF50) else Color.Gray)
                            )
                            Text(text = "Akun 1")
                        }
                    }
                )
                Tab(
                    selected = selectedAccount == 2,
                    onClick = { viewModel.selectAccount(2) },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isAccount2LoggedIn) Color(0xFF4CAF50) else Color.Gray)
                            )
                            Text(text = "Akun 2")
                        }
                    }
                )
            }

            // Card: Sinkronisasi Multi-Account RPC
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Sinkronisasi Aktivitas Multi-Akun",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Aktivitas Rich Presence (Game/Media/Custom) akan disiarkan ke akun yang diaktifkan secara bersamaan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "Aktivitas di Akun 1", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = if (isAccount1LoggedIn) "Terhubung" else "Belum Login",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isAccount1LoggedIn) Color(0xFF4CAF50) else Color.Gray
                            )
                        }
                        Switch(
                            checked = account1RpcEnabled && isAccount1LoggedIn,
                            onCheckedChange = { viewModel.setRpcEnabled(1, it) },
                            enabled = isAccount1LoggedIn
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "Aktivitas di Akun 2", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = if (isAccount2LoggedIn) "Terhubung" else "Belum Login",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isAccount2LoggedIn) Color(0xFF4CAF50) else Color.Gray
                            )
                        }
                        Switch(
                            checked = account2RpcEnabled && isAccount2LoggedIn,
                            onCheckedChange = { viewModel.setRpcEnabled(2, it) },
                            enabled = isAccount2LoggedIn
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Display current selected account state
            when (val state = currentState) {
                is UserState.Loading -> {
                    AnimatedShimmer {
                        ShimmerProfileCard(brush = it)
                    }
                }
                is UserState.LoadingCompleted -> {
                    if (state.user != null) {
                        ProfileCard(state.user)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Keluar dari Akun $selectedAccount?",
                                        actionLabel = "Ya, Keluar",
                                        duration = SnackbarDuration.Short,
                                        withDismissAction = true
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.logoutAccount(selectedAccount)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(text = "Keluar dari Akun $selectedAccount")
                        }
                    } else {
                        NotLoggedInView(
                            accountIndex = selectedAccount,
                            onLoginWebClicked = { showLoginBottomSheet = true },
                            onManualTokenClicked = {
                                manualTokenInput = ""
                                showManualTokenDialog = true
                            }
                        )
                    }
                }
                is UserState.Error -> {
                    if (state.user != null) {
                        ProfileNetworkError(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            error = state.error
                        )
                        ProfileCard(state.user)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Keluar dari Akun $selectedAccount?",
                                        actionLabel = "Ya, Keluar",
                                        duration = SnackbarDuration.Short,
                                        withDismissAction = true
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        viewModel.logoutAccount(selectedAccount)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(text = "Keluar dari Akun $selectedAccount")
                        }
                    } else {
                        NotLoggedInView(
                            accountIndex = selectedAccount,
                            onLoginWebClicked = { showLoginBottomSheet = true },
                            onManualTokenClicked = {
                                manualTokenInput = ""
                                showManualTokenDialog = true
                            }
                        )
                    }
                }
            }
        }
    }

    if (showLoginBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLoginBottomSheet = false },
            sheetState = modalBottomSheetState,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            DiscordLoginWebView { token ->
                scope.launch {
                    modalBottomSheetState.hide()
                    showLoginBottomSheet = false
                    viewModel.loginAccount(token, selectedAccount)
                }
            }
        }
    }

    if (showManualTokenDialog) {
        AlertDialog(
            onDismissRequest = { showManualTokenDialog = false },
            title = { Text(text = "Masukkan Token Akun $selectedAccount") },
            text = {
                Column {
                    Text(
                        text = "Tempelkan token Discord Anda di bawah ini:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualTokenInput,
                        onValueChange = { manualTokenInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Token Discord") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val token = manualTokenInput.trim()
                        if (token.isNotEmpty()) {
                            showManualTokenDialog = false
                            viewModel.loginAccount(token, selectedAccount)
                        }
                    }
                ) {
                    Text("Masuk")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualTokenDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }
}

@Composable
private fun NotLoggedInView(
    accountIndex: Int,
    onLoginWebClicked: () -> Unit,
    onManualTokenClicked: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_discord),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Akun $accountIndex Belum Terhubung",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Hubungkan akun Discord untuk menyiarkan aktivitas Rich Presence secara otomatis.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))
            DiscordLoginButton(onClick = onLoginWebClicked, enabled = true)
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onManualTokenClicked,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                Text("Masukkan Token Manual")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserScreen(
    state: UserState,
    onBackPressed: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier
            .fillMaxSize(),
        topBar = {
            TopAppBar(title = { },
                navigationIcon = { BackButton { onBackPressed() } })
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when (state) {
                is UserState.Error -> {
                    ProfileNetworkError(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp)
                            .align(Alignment.TopCenter),
                        error = (state.error)
                    )
                    ProfileCard(state.user)
                }

                UserState.Loading -> {
                    AnimatedShimmer {
                        ShimmerProfileCard(brush = it)
                    }
                }

                is UserState.LoadingCompleted -> {
                    ProfileCard(state.user)
                    Logout(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = ctx.getString(R.string.are_you_sure),
                                actionLabel = ctx.getString(R.string.yes),
                                duration = SnackbarDuration.Short,
                                withDismissAction = true
                            ).run {
                                when (this) {
                                    SnackbarResult.ActionPerformed -> try {
                                        android.webkit.CookieManager.getInstance().removeAllCookies {
                                            android.webkit.CookieManager.getInstance().flush()
                                            android.webkit.WebStorage.getInstance().deleteAllData()
                                            com.my.kizzy.preference.Prefs.clearAccount(1)
                                            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                                            intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            ctx.startActivity(intent)
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }

                                    SnackbarResult.Dismissed -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

val fakeUser = User(
    accentColor = null,
    avatar = null,
    avatarDecoration = null,
    badges = null,
    banner = null,
    bannerColor = null,
    discriminator = "3050",
    id = null,
    publicFlags = null,
    username = "yzziK",
    special = null,
    verified = false,
    nitro = true,
    bio = "Hello 👋"
)

@Preview
@Composable
fun UserScreenPreview() {
    UserScreen(
        state = UserState.Loading,
        onBackPressed = {},
    )
}
