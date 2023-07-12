/*
 * Copyright (c) 2022 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.autofill.impl

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.duckduckgo.app.di.AppCoroutineScope
import com.duckduckgo.app.global.DefaultDispatcherProvider
import com.duckduckgo.app.global.DispatcherProvider
import com.duckduckgo.app.utils.ConflatedJob
import com.duckduckgo.autofill.api.AutofillCapabilityChecker
import com.duckduckgo.autofill.api.Callback
import com.duckduckgo.autofill.api.domain.app.LoginCredentials
import com.duckduckgo.autofill.api.domain.app.LoginTriggerType
import com.duckduckgo.autofill.api.passwordgeneration.AutomaticSavedLoginsMonitor
import com.duckduckgo.autofill.api.store.AutofillStore
import com.duckduckgo.autofill.impl.domain.javascript.JavascriptCredentials
import com.duckduckgo.autofill.impl.jsbridge.AutofillMessagePoster
import com.duckduckgo.autofill.impl.jsbridge.request.AutofillDataRequest
import com.duckduckgo.autofill.impl.jsbridge.request.AutofillRequestParser
import com.duckduckgo.autofill.impl.jsbridge.request.AutofillStoreFormDataRequest
import com.duckduckgo.autofill.impl.jsbridge.request.SupportedAutofillInputMainType.CREDENTIALS
import com.duckduckgo.autofill.impl.jsbridge.request.SupportedAutofillInputSubType.PASSWORD
import com.duckduckgo.autofill.impl.jsbridge.request.SupportedAutofillInputSubType.USERNAME
import com.duckduckgo.autofill.impl.jsbridge.request.SupportedAutofillTriggerType
import com.duckduckgo.autofill.impl.jsbridge.request.SupportedAutofillTriggerType.AUTOPROMPT
import com.duckduckgo.autofill.impl.jsbridge.request.SupportedAutofillTriggerType.USER_INITIATED
import com.duckduckgo.autofill.impl.jsbridge.response.AutofillResponseWriter
import com.duckduckgo.autofill.impl.ui.credential.passwordgeneration.Actions
import com.duckduckgo.autofill.impl.ui.credential.passwordgeneration.Actions.DeleteAutoLogin
import com.duckduckgo.autofill.impl.ui.credential.passwordgeneration.Actions.DiscardAutoLoginId
import com.duckduckgo.autofill.impl.ui.credential.passwordgeneration.Actions.PromptToSave
import com.duckduckgo.autofill.impl.ui.credential.passwordgeneration.Actions.UpdateSavedAutoLogin
import com.duckduckgo.autofill.impl.ui.credential.passwordgeneration.AutogeneratedPasswordEventResolver
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.di.scopes.FragmentScope
import com.squareup.anvil.annotations.ContributesBinding
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

interface AutofillJavascriptInterface {

    @JavascriptInterface
    fun getAutofillData(requestString: String)

    fun injectCredentials(credentials: LoginCredentials)
    fun injectNoCredentials()

    fun cancelRetrievingStoredLogins()

    fun acceptGeneratedPassword()
    fun rejectGeneratedPassword()

    var callback: Callback?
    var webView: WebView?
    var autoSavedLoginsMonitor: AutomaticSavedLoginsMonitor?
    var tabId: String?

    companion object {
        const val INTERFACE_NAME = "BrowserAutofill"
    }
}

@ContributesBinding(FragmentScope::class)
class AutofillStoredBackJavascriptInterface @Inject constructor(
    private val requestParser: AutofillRequestParser,
    private val autofillStore: AutofillStore,
    private val autofillMessagePoster: AutofillMessagePoster,
    private val autofillResponseWriter: AutofillResponseWriter,
    @AppCoroutineScope private val coroutineScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    private val currentUrlProvider: UrlProvider = WebViewUrlProvider(dispatcherProvider),
    private val autofillCapabilityChecker: AutofillCapabilityChecker,
    private val passwordEventResolver: AutogeneratedPasswordEventResolver,
) : AutofillJavascriptInterface {

    override var callback: Callback? = null
    override var webView: WebView? = null
    override var autoSavedLoginsMonitor: AutomaticSavedLoginsMonitor? = null
    override var tabId: String? = null

    // coroutine jobs tracked for supporting cancellation
    private val getAutofillDataJob = ConflatedJob()
    private val storeFormDataJob = ConflatedJob()
    private val injectCredentialsJob = ConflatedJob()

    @JavascriptInterface
    override fun getAutofillData(requestString: String) {
        Timber.v("BrowserAutofill: getAutofillData called:\n%s", requestString)
        getAutofillDataJob += coroutineScope.launch(dispatcherProvider.default()) {
            val url = currentUrlProvider.currentUrl(webView)
            if (url == null) {
                Timber.w("Can't autofill as can't retrieve current URL")
                return@launch
            }

            if (!autofillCapabilityChecker.canInjectCredentialsToWebView(url)) {
                Timber.v("BrowserAutofill: getAutofillData called but feature is disabled")
                return@launch
            }

            val request = requestParser.parseAutofillDataRequest(requestString)
            val triggerType = convertTriggerType(request.trigger)

            if (request.mainType != CREDENTIALS) {
                handleUnknownRequestMainType(request, url)
                return@launch
            }

            if (request.isGeneratedPasswordAvailable()) {
                handleRequestForPasswordGeneration(url, request)
            } else if (request.isAutofillCredentialsRequest()) {
                handleRequestForAutofillingCredentials(url, request, triggerType)
            } else {
                Timber.w("Unable to process request; don't know how to handle request %s", requestString)
            }
        }
    }

    private suspend fun handleRequestForPasswordGeneration(
        url: String,
        request: AutofillDataRequest,
    ) {
        callback?.onGeneratedPasswordAvailableToUse(url, request.generatedPassword?.username, request.generatedPassword?.value!!)
    }

    private suspend fun handleRequestForAutofillingCredentials(
        url: String,
        request: AutofillDataRequest,
        triggerType: LoginTriggerType,
    ) {
        val allCredentials = autofillStore.getCredentials(url)
        val credentials = filterRequestedSubtypes(request, allCredentials)

        if (credentials.isEmpty()) {
            callback?.noCredentialsAvailable(url)
        } else {
            callback?.onCredentialsAvailableToInject(url, credentials, triggerType)
        }
    }

    private fun convertTriggerType(trigger: SupportedAutofillTriggerType): LoginTriggerType {
        return when (trigger) {
            USER_INITIATED -> LoginTriggerType.USER_INITIATED
            AUTOPROMPT -> LoginTriggerType.AUTOPROMPT
        }
    }

    private fun filterRequestedSubtypes(
        request: AutofillDataRequest,
        credentials: List<LoginCredentials>,
    ): List<LoginCredentials> {
        return when (request.subType) {
            USERNAME -> credentials.filterNot { it.username.isNullOrBlank() }
            PASSWORD -> credentials.filterNot { it.password.isNullOrBlank() }
        }
    }

    private fun handleUnknownRequestMainType(
        request: AutofillDataRequest,
        url: String,
    ) {
        Timber.w("Autofill type %s unsupported", request.mainType)
        callback?.noCredentialsAvailable(url)
    }

    @JavascriptInterface
    fun storeFormData(data: String) {
        Timber.i("storeFormData called, credentials provided to be persisted")

        storeFormDataJob += coroutineScope.launch(dispatcherProvider.default()) {
            val currentUrl = currentUrlProvider.currentUrl(webView) ?: return@launch

            if (!autofillCapabilityChecker.canSaveCredentialsFromWebView(currentUrl)) {
                Timber.v("BrowserAutofill: storeFormData called but feature is disabled")
                return@launch
            }

            val request = requestParser.parseStoreFormDataRequest(data)

            if (!request.isValid()) {
                Timber.w("Invalid data from storeFormData")
                return@launch
            }

            val jsCredentials = JavascriptCredentials(request.credentials!!.username, request.credentials.password)
            val credentials = jsCredentials.asLoginCredentials(currentUrl)

            val autologinId = autoSavedLoginsMonitor?.getAutoSavedLoginId(tabId)
            Timber.i("Autogenerated? %s, Previous autostored login ID: %s", request.credentials.autogenerated, autologinId)
            val autosavedLogin = autologinId?.let { autofillStore.getCredentialsWithId(it) }

            val actions = passwordEventResolver.decideActions(autosavedLogin, request.credentials.autogenerated)
            processStoreFormDataActions(actions, currentUrl, credentials, autologinId)
        }
    }

    private suspend fun processStoreFormDataActions(
        actions: List<Actions>,
        currentUrl: String,
        credentials: LoginCredentials,
        autologinId: Long?,
    ) {
        Timber.d("%d actions to take: %s", actions.size, actions.joinToString())
        actions.forEach {
            when (it) {
                is DeleteAutoLogin -> {
                    autofillStore.deleteCredentials(it.autologinId)
                }

                is DiscardAutoLoginId -> {
                    autoSavedLoginsMonitor?.clearAutoSavedLoginId(tabId)
                }

                is PromptToSave -> {
                    callback?.onCredentialsAvailableToSave(currentUrl, credentials)
                }

                is UpdateSavedAutoLogin -> {
                    autofillStore.getCredentialsWithId(it.autologinId)?.let { existingCredentials ->
                        if (isUpdateRequired(existingCredentials, credentials)) {
                            Timber.v("Update required as not identical to what is already stored. id=%s", it.autologinId)
                            val toSave = existingCredentials.copy(username = credentials.username, password = credentials.password)
                            autofillStore.updateCredentials(toSave)?.let { savedCredentials ->
                                callback?.onCredentialsSaved(savedCredentials)
                            }
                        } else {
                            Timber.v("Update not required as identical to what is already stored. id=%s", it.autologinId)
                            callback?.onCredentialsSaved(existingCredentials)
                        }
                    }
                }
            }
        }
    }

    private fun isUpdateRequired(
        existingCredentials: LoginCredentials,
        credentials: LoginCredentials,
    ): Boolean {
        return existingCredentials.username != credentials.username || existingCredentials.password != credentials.password
    }

    private fun AutofillStoreFormDataRequest?.isValid(): Boolean {
        if (this == null || credentials == null) return false
        return !(credentials.username.isNullOrBlank() && credentials.password.isNullOrBlank())
    }

    override fun injectCredentials(credentials: LoginCredentials) {
        Timber.v("Informing JS layer with credentials selected")
        injectCredentialsJob += coroutineScope.launch(dispatcherProvider.default()) {
            val jsCredentials = credentials.asJsCredentials()
            autofillMessagePoster.postMessage(webView, autofillResponseWriter.generateResponseGetAutofillData(jsCredentials))
        }
    }

    override fun injectNoCredentials() {
        Timber.v("No credentials selected; informing JS layer")
        injectCredentialsJob += coroutineScope.launch(dispatcherProvider.io()) {
            autofillMessagePoster.postMessage(webView, autofillResponseWriter.generateEmptyResponseGetAutofillData())
        }
    }

    private fun LoginCredentials.asJsCredentials(): JavascriptCredentials {
        return JavascriptCredentials(
            username = username,
            password = password,
        )
    }

    override fun cancelRetrievingStoredLogins() {
        getAutofillDataJob.cancel()
    }

    override fun acceptGeneratedPassword() {
        Timber.v("Accepting generated password")
        injectCredentialsJob += coroutineScope.launch(dispatcherProvider.io()) {
            autofillMessagePoster.postMessage(webView, autofillResponseWriter.generateResponseForAcceptingGeneratedPassword())
        }
    }

    override fun rejectGeneratedPassword() {
        Timber.v("Rejecting generated password")
        injectCredentialsJob += coroutineScope.launch(dispatcherProvider.io()) {
            autofillMessagePoster.postMessage(webView, autofillResponseWriter.generateResponseForRejectingGeneratedPassword())
        }
    }

    private fun JavascriptCredentials.asLoginCredentials(
        url: String,
    ): LoginCredentials {
        return LoginCredentials(
            id = null,
            domain = url,
            username = username,
            password = password,
            domainTitle = null,
        )
    }

    interface UrlProvider {
        suspend fun currentUrl(webView: WebView?): String?
    }

    @ContributesBinding(AppScope::class)
    class WebViewUrlProvider @Inject constructor(val dispatcherProvider: DispatcherProvider) : UrlProvider {
        override suspend fun currentUrl(webView: WebView?): String? {
            return withContext(dispatcherProvider.main()) {
                webView?.url
            }
        }
    }
}