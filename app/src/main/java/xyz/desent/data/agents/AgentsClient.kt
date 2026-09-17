package xyz.desent.data.agents

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.desent.crypto.NostrHttpAuth
import xyz.desent.data.agents.model.AgentDto
import xyz.desent.data.agents.model.AgentListResponse
import xyz.desent.data.agents.model.AgentModeDto
import xyz.desent.data.agents.model.AgentsError
import xyz.desent.data.agents.model.AgentErrorResponse
import xyz.desent.data.agents.model.CreateAgentRequest
import xyz.desent.data.agents.model.DeleteAgentResponse
import xyz.desent.data.agents.model.UpdateAgentRequest

/**
 * OkHttp REST client for the DeSent AI-agents API (ANDROID_AI_AGENTS.md /
 * END-21).
 *
 * Base URL defaults to `https://desent.xyz/api/agents`. Auth (NIP-98) is
 * built per-request via [NostrHttpAuth]. Only the agent's PUBLIC key is
 * ever sent — the private key, passphrase, and ncryptsec connection code
 * never appear in any request body.
 */
class AgentsClient(
    private val okHttpClient: OkHttpClient,
    private val auth: NostrHttpAuth,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /** GET / — list caller's agents + cap/tier snapshot. */
    suspend fun listAgents(): Result<AgentListResponse> = withContext(Dispatchers.IO) {
        runRequest<AgentListResponse>(
            url = baseUrl,
            method = "GET",
            authRequired = true
        )
    }

    /** POST / — register an agent. [agentPubkey] is the 64-hex x-only key. */
    suspend fun createAgent(
        agentPubkey: String,
        addressLocal: String,
        mode: AgentModeDto,
        label: String?
    ): Result<AgentDto> = withContext(Dispatchers.IO) {
        val bodyStr = json.encodeToString(
            CreateAgentRequest.serializer(),
            CreateAgentRequest(
                agentPubkey = agentPubkey,
                addressLocal = addressLocal,
                mode = mode.serialName,
                label = label
            )
        )
        runRequest<AgentDto>(
            url = baseUrl,
            method = "POST",
            authRequired = true,
            bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
        )
    }

    /** PATCH /{id} — update mode/keywords/policy/persona/profile (full-list replacement). */
    suspend fun updateAgent(
        id: Long,
        mode: AgentModeDto?,
        triggerKeywords: List<String>?,
        policyNote: String?,
        systemPrompt: String? = null,
        displayName: String? = null,
        pictureUrl: String? = null,
        about: String? = null
    ): Result<AgentDto> = withContext(Dispatchers.IO) {
        val bodyStr = json.encodeToString(
            UpdateAgentRequest.serializer(),
            UpdateAgentRequest(
                mode = mode?.serialName,
                triggerKeywords = triggerKeywords,
                policyNote = policyNote,
                systemPrompt = systemPrompt,
                displayName = displayName,
                pictureUrl = pictureUrl,
                about = about
            )
        )
        runRequest<AgentDto>(
            url = "$baseUrl/$id",
            method = "PATCH",
            authRequired = true,
            bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
        )
    }

    /** DELETE /{id} — hard revoke: address frees, agent loses read + send. */
    suspend fun deleteAgent(id: Long): Result<DeleteAgentResponse> =
        withContext(Dispatchers.IO) {
            runRequest<DeleteAgentResponse>(
                url = "$baseUrl/$id",
                method = "DELETE",
                authRequired = true
            )
        }

    private suspend inline fun <reified T> runRequest(
        url: String,
        method: String,
        authRequired: Boolean,
        bodyBytes: ByteArray? = null
    ): Result<T> {
        return try {
            val requestBuilder = Request.Builder().url(url)

            if (authRequired) {
                val header = auth.buildAuthHeader(url, method, bodyBytes).getOrThrow()
                requestBuilder.header("Authorization", header)
            }

            when (method) {
                "GET" -> requestBuilder.get()
                "DELETE" -> requestBuilder.delete()
                "POST", "PATCH" -> {
                    requestBuilder.header("Content-Type", "application/json")
                    requestBuilder.method(
                        method,
                        (bodyBytes ?: ByteArray(0)).toRequestBody("application/json".toMediaType())
                    )
                }
            }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                val error = parseError(response.code, responseBody)
                Log.w(TAG, "$method $url -> HTTP ${response.code}: ${error.message}")
                return Result.failure(error)
            }

            if (responseBody.isNullOrBlank()) {
                return Result.failure(AgentsError.Unknown("Empty response body"))
            }

            val parsed = json.decodeFromString<T>(responseBody)
            Log.d(TAG, "$method $url -> HTTP ${response.code} OK")
            Result.success(parsed)
        } catch (e: AgentsError) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "$method $url failed: ${e.message}", e)
            Result.failure(AgentsError.Unknown(e.message ?: "Network error"))
        }
    }

    private fun parseError(code: Int, body: String?): AgentsError {
        val parsed = try {
            json.decodeFromString<AgentErrorResponse>(body ?: "{}").detail
        } catch (e: Exception) {
            null
        }
        val codeStr = parsed?.error

        return when (code) {
            401 -> AgentsError.Unauthorized
            402 -> when (codeStr) {
                "vanity_price" -> AgentsError.VanityPrice(
                    length = parsed?.length ?: 0,
                    priceSats = parsed?.priceSats ?: 0L,
                    ladder = parsed?.ladder ?: emptyMap(),
                    freeLength = parsed?.freeLength ?: 8
                )
                "agent_cap_reached" -> AgentsError.CapReached(
                    cap = parsed?.cap,
                    used = parsed?.used,
                    tier = parsed?.tier
                )
                else -> AgentsError.Server(codeStr ?: "Payment required", 402)
            }
            403 -> AgentsError.Disabled
            404 -> AgentsError.NotFound
            409 -> when (codeStr) {
                "agent_already_registered" -> AgentsError.AlreadyRegistered
                else -> AgentsError.Taken
            }
            422 -> when (codeStr) {
                "invalid_local_part" -> AgentsError.InvalidLocalPart
                "invalid_pubkey" -> AgentsError.InvalidPubkey
                "invalid_mode" -> AgentsError.InvalidMode
                "invalid_domain" -> AgentsError.InvalidDomain
                else -> AgentsError.Unknown(codeStr ?: "Validation error")
            }
            429 -> AgentsError.RateLimited
            else -> AgentsError.Server(codeStr ?: "Server error", code)
        }
    }

    companion object {
        private const val TAG = "AgentsClient"
        const val DEFAULT_BASE_URL = "https://desent.xyz/api/agents"
    }
}

/** Wire name used by the API for this mode. */
val AgentModeDto.serialName: String
    get() = when (this) {
        AgentModeDto.DIGEST -> "digest"
        AgentModeDto.RESPOND -> "respond"
    }
