package ru.tgmaksim.activium.ui.core

import androidx.lifecycle.ViewModel
import io.ktor.network.tls.TlsException
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CancellationException

import ru.tgmaksim.activium.R
import ru.tgmaksim.activium.api.Request
import ru.tgmaksim.activium.api.ApiResponse
import ru.tgmaksim.activium.api.Status
import ru.tgmaksim.activium.utilities.Utilities

open class UiViewModel : ViewModel() {
    protected suspend fun <R : ApiResponse, T> executeRequest(
        state: MutableStateFlow<LoadState<T>>,
        apiName: String,
        errorRes: Int,
        request: suspend () -> R,
        mapSuccess: (R) -> T?,
        onSuccess: suspend (T) -> Unit = {}
    ) {
        executeRequest(state, {}, apiName, errorRes, request, mapSuccess, onSuccess)
    }

    protected suspend fun <R : ApiResponse, T> executeRequest(
        state: MutableStateFlow<LoadState<T>>,
        onNewState: (LoadState<Nothing>) -> Unit,
        apiName: String,
        errorRes: Int,
        request: suspend () -> R,
        mapSuccess: (R) -> T?,
        onSuccess: suspend (T) -> Unit = {}
    ) {
        onNewState(state.setLoading())
        var loading = true

        try {
            val response = request()
            val answer = mapSuccess(response)

            if (!response.status || answer == null) {
                if (response.error != null)
                    Utilities.log("API error(${response.error?.type}) at $apiName: ${response.error?.errorMessage}")

                val unauthorized = response.error?.type == "UnauthorizedError"
                val message = response.error?.errorMessage?.let {
                    UiText.DynamicString(it)
                } ?: UiText.StringResource(errorRes)

                onNewState(state.setError(message, unauthorized))
                loading = false
            } else {
                state.setSuccess(answer)
                loading = false
                onSuccess(answer)
            }
        } catch (_: CancellationException) {
            onNewState(state.setError(UiText.StringResource(errorRes)))
            loading = false
        } catch (_: TlsException) {
            onNewState(state.setError(UiText.StringResource(R.string.error_server)))
            loading = false
        } catch (e: Exception) {
            val messageRes = when {
                !Request.checkInternet() -> R.string.error_internet
                !Status.checkHealth() -> R.string.error_server
                else -> {
                    Utilities.log(e, "Error at $apiName")
                    errorRes
                }
            }
            onNewState(state.setError(UiText.StringResource(messageRes)))
            loading = false
        } finally {
            if (loading)
                onNewState(state.setError(UiText.StringResource(errorRes)))
        }
    }

    protected suspend fun <R: ApiResponse, T> executeRequest(
        state: MutableStateFlow<CacheDataLoadState>,
        dataState: MutableStateFlow<T?>,
        apiName: String,
        errorRes: Int,
        request: suspend () -> R,
        mapSuccess: (R) -> T?,
        onSuccess: suspend (R) -> Unit = {}
    ) {
        state.setCloudLoading()
        var loading = true

        try {
            val response = request()
            val answer = mapSuccess(response)

            if (!response.status || answer == null) {
                if (response.error != null)
                    Utilities.log("API error(${response.error?.type}) at $apiName: ${response.error?.errorMessage}")

                val unauthorized = response.error?.type == "UnauthorizedError"
                val message = response.error?.errorMessage?.let {
                    UiText.DynamicString(it)
                } ?: UiText.StringResource(errorRes)

                state.setCloudError(message, unauthorized)
                loading = false
            } else {
                dataState.value = answer
                state.setCloudSuccess()
                loading = false
                onSuccess(response)
            }
        } catch (_: CancellationException) {
            // Запущена другая задача
            loading = false
        } catch (_: TlsException) {
            state.setCloudError(UiText.StringResource(R.string.error_server))
            loading = false
        } catch (e: Exception) {
            val messageRes = when {
                !Request.checkInternet() -> R.string.error_internet
                !Status.checkHealth() -> R.string.error_server
                else -> {
                    Utilities.log(e, "Error at $apiName")
                    errorRes
                }
            }
            state.setCloudError(UiText.StringResource(messageRes))
            loading = false
        } finally {
            if (loading)
                state.setCloudError(UiText.StringResource(errorRes))
        }
    }

    protected suspend fun <K, R : ApiResponse, T> executeRequest(
        state: MutableStateFlow<Map<K, LoadState<T>>>,
        stateKey: K,
        apiName: String,
        errorRes: Int,
        request: suspend () -> R,
        mapSuccess: (R) -> T?,
        onSuccess: suspend (T) -> Unit = {}
    ) {
        executeRequest(state, stateKey, {}, apiName, errorRes, request, mapSuccess, onSuccess)
    }

    protected suspend fun <K, R : ApiResponse, T> executeRequest(
        state: MutableStateFlow<Map<K, LoadState<T>>>,
        stateKey: K,
        onNewState: (LoadState<Nothing>) -> Unit,
        apiName: String,
        errorRes: Int,
        request: suspend () -> R,
        mapSuccess: (R) -> T?,
        onSuccess: suspend (T) -> Unit = {}
    ) {
        onNewState(state.setLoading(stateKey))
        var loading = true

        try {
            val response = request()
            val answer = mapSuccess(response)

            if (!response.status || answer == null) {
                if (response.error != null)
                    Utilities.log("API error(${response.error?.type}) at $apiName: ${response.error?.errorMessage}")

                val unauthorized = response.error?.type == "UnauthorizedError"
                val message = response.error?.errorMessage?.let {
                    UiText.DynamicString(it)
                } ?: UiText.StringResource(errorRes)

                onNewState(state.setError(stateKey, message, unauthorized))
                loading = false
            } else {
                state.setSuccess(stateKey, answer)
                loading = false
                onSuccess(answer)
            }
        } catch (_: CancellationException) {
            // Запущена другая задача
            loading = false
        } catch (_: TlsException) {
            onNewState(state.setError(stateKey, UiText.StringResource(R.string.error_server)))
            loading = false
        } catch (e: Exception) {
            val messageRes = when {
                !Request.checkInternet() -> R.string.error_internet
                !Status.checkHealth() -> R.string.error_server
                else -> {
                    Utilities.log(e, "Error at $apiName")
                    errorRes
                }
            }
            onNewState(state.setError(stateKey, UiText.StringResource(messageRes)))
            loading = false
        } finally {
            if (loading)
                onNewState(state.setError(stateKey, UiText.StringResource(errorRes)))
        }
    }

    protected class CacheNullException : Exception()
}