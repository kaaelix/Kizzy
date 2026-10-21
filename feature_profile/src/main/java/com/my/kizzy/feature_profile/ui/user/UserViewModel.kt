/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * UserViewModel.kt is part of Kizzy
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

package com.my.kizzy.feature_profile.ui.user

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.my.kizzy.domain.model.Resource
import com.my.kizzy.domain.model.user.User
import com.my.kizzy.domain.use_case.get_user.GetUserUseCase
import com.my.kizzy.feature_profile.getUserInfo
import com.my.kizzy.preference.Prefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class UserViewModel @Inject constructor(
    private val getUserUseCase: GetUserUseCase
): ViewModel() {

    private val _selectedAccount: MutableState<Int> = mutableStateOf(Prefs[Prefs.SELECTED_ACCOUNT_INDEX, 1])
    val selectedAccount: State<Int> = _selectedAccount

    private val _account1State: MutableState<UserState> = mutableStateOf(UserState.Loading)
    val account1State: State<UserState> = _account1State

    private val _account2State: MutableState<UserState> = mutableStateOf(UserState.Loading)
    val account2State: State<UserState> = _account2State

    private val _account1RpcEnabled = mutableStateOf(Prefs[Prefs.ACCOUNT_1_RPC_ENABLED, true])
    val account1RpcEnabled: State<Boolean> = _account1RpcEnabled

    private val _account2RpcEnabled = mutableStateOf(Prefs[Prefs.ACCOUNT_2_RPC_ENABLED, true])
    val account2RpcEnabled: State<Boolean> = _account2RpcEnabled

    val state: State<UserState>
        get() = if (_selectedAccount.value == 2) _account2State else _account1State

    init {
        loadAccount(1)
        loadAccount(2)
    }

    fun selectAccount(index: Int) {
        _selectedAccount.value = index
        Prefs[Prefs.SELECTED_ACCOUNT_INDEX] = index
    }

    fun setRpcEnabled(index: Int, enabled: Boolean) {
        if (index == 1) {
            _account1RpcEnabled.value = enabled
            Prefs[Prefs.ACCOUNT_1_RPC_ENABLED] = enabled
        } else {
            _account2RpcEnabled.value = enabled
            Prefs[Prefs.ACCOUNT_2_RPC_ENABLED] = enabled
        }
    }

    fun loadAccount(index: Int) {
        val token = Prefs.getToken(index)
        val stateRef = if (index == 1) _account1State else _account2State
        val userIdKey = if (index == 1) Prefs.USER_ID else Prefs.USER_ID_2
        val userBioKey = if (index == 1) Prefs.USER_BIO else Prefs.USER_BIO_2
        val userNitroKey = if (index == 1) Prefs.USER_NITRO else Prefs.USER_NITRO_2
        val userDataKey = if (index == 1) Prefs.USER_DATA else Prefs.USER_DATA_2

        if (token.isBlank()) {
            stateRef.value = UserState.Error(
                error = "Not logged in",
                user = null
            )
            return
        }

        val userId = Prefs[userIdKey, ""]
        if (userId.isEmpty()) {
            viewModelScope.launch {
                stateRef.value = UserState.Loading
                try {
                    getUserInfo(token, index) {
                        loadAccount(index)
                    }
                } catch (e: Exception) {
                    stateRef.value = UserState.Error(
                        error = e.localizedMessage ?: "Failed to get user info",
                        user = null
                    )
                }
            }
            return
        }

        getUserUseCase(userId).onEach { result ->
            when (result) {
                is Resource.Success -> {
                    val user = result.data?.copy(
                        bio = Prefs[userBioKey, ""],
                        nitro = Prefs[userNitroKey, false]
                    )
                    stateRef.value = UserState.LoadingCompleted(user = user)
                    Prefs[userDataKey] = Json.encodeToString(result.data)
                }
                is Resource.Error -> {
                    val cachedJson = Prefs[userDataKey, ""]
                    val cachedUser = if (cachedJson.isNotEmpty()) {
                        runCatching { Json.decodeFromString<User>(cachedJson) }.getOrNull()
                    } else null
                    stateRef.value = UserState.Error(
                        error = result.message ?: "An unexpected error occurred",
                        user = cachedUser
                    )
                }
                is Resource.Loading -> {
                    stateRef.value = UserState.Loading
                }
            }
        }.launchIn(viewModelScope)
    }

    fun loginAccount(token: String, index: Int, onComplete: () -> Unit = {}) {
        Prefs.setToken(index, token)
        val stateRef = if (index == 1) _account1State else _account2State
        stateRef.value = UserState.Loading
        viewModelScope.launch {
            try {
                getUserInfo(token, index) {
                    loadAccount(index)
                    onComplete()
                }
            } catch (e: Exception) {
                stateRef.value = UserState.Error(
                    error = e.localizedMessage ?: "Login failed",
                    user = null
                )
            }
        }
    }

    fun logoutAccount(index: Int) {
        Prefs.clearAccount(index)
        val stateRef = if (index == 1) _account1State else _account2State
        stateRef.value = UserState.Error(
            error = "Not logged in",
            user = null
        )
    }
}