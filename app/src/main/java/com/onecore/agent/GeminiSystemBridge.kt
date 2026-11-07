package com.onecore.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class GeminiSystemBridge(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val systemInstruction = """
        Tu es OneCore, un agent système IA implanté au cœur d'un smartphone Samsung Galaxy sous One UI.
        Tu n'es pas un simple chatbot : tu as un accès direct aux tables de paramètres Android/One UI, au terminal Shell/Root et au service d'accessibilité.
        
        Clés One UI / Samsung utiles pour 'modify_samsung_setting' :
        - global : animator_duration_scale, transition_animation_scale, window_animation_scale (animations, ex: 0.5)
        - global : sem_enhanced_cpu_responsiveness (0 = Standard, 1 = Traitement amélioré / Performance)
        - system : aod_mode (0 = Off, 1 = Always On Display activé)
        - system : blue_light_filter (0 = Off, 1 = Confort visuel activé)
        - system : screen_off_timeout (temps avant extinction en ms, ex: 60000)
        - secure : ui_night_mode (1 = Clair, 2 = Sombre)
        - global : adb_enabled (0 ou 1)
        
        Si un réglage ne peut pas être changé par clé, ouvre les paramètres via 'run_shell_command' (ex: 'am start -a android.settings.SETTINGS'), utilise 'inspect_screen' pour lire l'écran, puis 'interact_ui' pour cliquer.
        Sois direct, concis et confirme chaque modification effectuée.
    """.trimIndent()

    suspend fun processCommand(
        apiKey: String,
        userPrompt: String,
        onLog: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val contents = JSONArray()
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
        })

        for (step in 1..6) {
            val responseJson = callGeminiApi(apiKey, contents) ?: return@withContext "Erreur de connexion à l'API Gemini."
            val candidates = responseJson.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return@withContext "Erreur API : ${responseJson.toString()}"
            }

            val content = candidates.getJSONObject(0).getJSONObject("content")
            contents.put(content)
            val parts = content.getJSONArray("parts")
            val firstPart = parts.getJSONObject(0)

            if (firstPart.has("functionCall")) {
                val fnCall = firstPart.getJSONObject("functionCall")
                val fnName = fnCall.getString("name")
                val args = fnCall.getJSONObject("args")

                onLog("⚡ Exécution : $fnName(${args.toString()})")
                val toolResult = executeTool(fnName, args)
                onLog("↳ Résultat : ${toolResult.take(120)}...")

                val fnResponsePart = JSONObject().apply {
                    put("functionResponse", JSONObject().apply {
                        put("name", fnName)
                        put("response", JSONObject().put("result", toolResult))
                    })
                }
                contents.put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(fnResponsePart))
                })
                delay(400)
            } else if (firstPart.has("text")) {
                return@withContext firstPart.getString("text")
            }
        }
        return@withContext "Cycle d'exécution terminé."
    }

    private fun executeTool(name: String, args: JSONObject): String {
        return try {
            when (name) {
                "modify_samsung_setting" -> {
                    val table = args.getString("table")
                    val key = args.getString("key")
                    val value = args.getString("value")
                    val resolver = context.contentResolver
                    val success = when (table.lowercase()) {
                        "system" -> Settings.System.putString(resolver, key, value)
                        "secure" -> Settings.Secure.putString(resolver, key, value)
                        "global" -> Settings.Global.putString(resolver, key, value)
                        else -> false
                    }
                    if (success) "Paramètre $table/$key modifié à '$value' avec succès."
                    else "Échec direct. Essaye via run_shell_command: settings put $table $key $value"
                }

                "run_shell_command" -> {
                    val cmd = args.getString("command")
                    val useRoot = args.optBoolean("use_root", false)
                    val process = if (useRoot) {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                    } else {
                        Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                    }
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val errReader = BufferedReader(InputStreamReader(process.errorStream))
                    val output = reader.readText() + errReader.readText()
                    process.waitFor()
                    output.ifEmpty { "Commande exécutée (code ${process.exitValue()})" }
                }

                "inspect_screen" -> {
                    AgentAccessibilityService.instance?.dumpScreenHierarchy()
                        ?: "Service d'accessibilité inactif."
                }

                "interact_ui" -> {
                    val svc = AgentAccessibilityService.instance
                        ?: return "Service d'accessibilité inactif."
                    val actionType = args.getString("action_type")
                    when (actionType) {
                        "click_text" -> svc.clickByText(args.getString("target"))
                        "tap_xy" -> {
                            val coords = args.getString("target").split(",")
                            svc.tapCoordinates(coords[0].trim().toFloat(), coords[1].trim().toFloat())
                        }
                        "back" -> { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK); "Retour effectué" }
                        "home" -> { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); "Accueil affiché" }
                        "quick_settings" -> { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS); "Volet de raccourcis ouvert" }
                        else -> "Action inconnue"
                    }
                }
                else -> "Outil non reconnu."
            }
        } catch (e: Exception) {
            "Erreur d'exécution : ${e.message}"
        }
    }

    private fun callGeminiApi(apiKey: String, contents: JSONArray): JSONObject? {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

        val tools = JSONArray().put(JSONObject().apply {
            put("functionDeclarations", JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "modify_samsung_setting")
                    put("description", "Modifie un paramètre Android ou Samsung One UI dans les tables system, secure ou global.")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("table", JSONObject().put("type", "STRING").put("description", "system, secure, ou global"))
                            put("key", JSONObject().put("type", "STRING").put("description", "Nom de la clé du paramètre"))
                            put("value", JSONObject().put("type", "STRING").put("description", "Nouvelle valeur"))
                        })
                        put("required", JSONArray().put("table").put("key").put("value"))
                    })
                })
                put(JSONObject().apply {
                    put("name", "run_shell_command")
                    put("description", "Exécute une commande shell Android (am, pm, settings, dumpsys, input) avec ou sans privilèges Root.")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("command", JSONObject().put("type", "STRING"))
                            put("use_root", JSONObject().put("type", "BOOLEAN"))
                        })
                        put("required", JSONArray().put("command"))
                    })
                })
                put(JSONObject().apply {
                    put("name", "inspect_screen")
                    put("description", "Lit tous les éléments visibles sur l'écran actuel et leurs coordonnées.")
                    put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
                })
                put(JSONObject().apply {
                    put("name", "interact_ui")
                    put("description", "Clique sur l'écran ou effectue une navigation système.")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("action_type", JSONObject().put("type", "STRING").put("description", "click_text, tap_xy, back, home, quick_settings"))
                            put("target", JSONObject().put("type", "STRING").put("description", "Texte à cliquer ou coordonnées 'X,Y'"))
                        })
                        put("required", JSONArray().put("action_type"))
                    })
                })
            })
        })

        val payload = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
            put("contents", contents)
            put("tools", tools)
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: return null
            return JSONObject(bodyStr)
        }
    }
}
