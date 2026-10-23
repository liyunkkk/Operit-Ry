package com.ai.assistance.operit.ui.common.dynamicui

import androidx.a2ui.engine.model.A2uiCoreSurfaceModel
import androidx.a2ui.model.protocol.A2uiDataPath
import kotlinx.serialization.json.*

/**
 * Local state transitions must read current state at consumption, not the earlier actor snapshot.
 * Caller applies this result immediately, without suspending. External submissions keep their
 * original click snapshot so confirmation and sent data stay identical.
 */
internal fun resolveLocalCardContext(
    surface: A2uiCoreSurfaceModel,
    componentId: String,
    component: JsonObject?,
    fallback: Map<String, Any?>,
    change: Boolean = false,
): Map<String, Any?> {
    val event = (component?.get(if (change) "onChange" else "action") as? JsonObject)?.get("event") as? JsonObject
    if ((event?.get("name") as? JsonPrimitive)?.content != "local_action") return fallback
    val raw = event["context"] ?: return fallback
    val definition = if (change) cardEventValues(raw, fallback) else raw
    val evaluated = surface.evaluatePayload(componentId, { surface.dataModel[it] }, A2uiDataPath("/"), DynamicUiProtocol.toValue(definition))
        as? Map<*, *> ?: error("Invalid local action")
    return evaluated.entries.associate { (key, value) -> (key as? String ?: error("Invalid local action key")) to value }
        .also { DynamicUiProtocol.toJson(it) }
}
