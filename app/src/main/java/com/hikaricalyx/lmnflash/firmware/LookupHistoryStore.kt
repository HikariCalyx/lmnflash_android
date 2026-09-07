package com.hikaricalyx.lmnflash.firmware

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A locally retained successful lookup summary with optional hidden form data for restoration. */
data class LookupHistoryRecord(
    val id: String,
    val recordedAt: Long,
    val mode: LookupMode,
    val identifier: String = "",
    val model: String = "",
    val marketName: String = "",
    val carrierOrCountry: String = "",
    val retcnForm: RetcnForm? = null,
    val modelForm: ModelForm? = null,
)

/** Stores every successful lookup summary without an application-imposed record limit. */
class LookupHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): List<LookupHistoryRecord> {
        val records = runCatching { JSONArray(preferences.getString(KEY_RECORDS, "[]")) }.getOrElse { return emptyList() }
        return buildList {
            for (index in 0 until records.length()) {
                records.optJSONObject(index)?.let(::decode)?.let(::add)
            }
        }.sortedByDescending(LookupHistoryRecord::recordedAt)
    }

    fun save(records: List<LookupHistoryRecord>) {
        val array = JSONArray()
        records.forEach { array.put(encode(it)) }
        preferences.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_RECORDS).apply()
    }

    private fun encode(record: LookupHistoryRecord): JSONObject = JSONObject()
        .put("id", record.id)
        .put("recordedAt", record.recordedAt)
        .put("mode", record.mode.name)
        .put("identifier", record.identifier)
        .put("model", record.model)
        .put("marketName", record.marketName)
        .put("carrierOrCountry", record.carrierOrCountry)
        .apply {
            record.retcnForm?.let { put("retcnForm", encodeRetcnForm(it)) }
            record.modelForm?.let { put("modelForm", encodeModelForm(it)) }
        }

    private fun decode(json: JSONObject): LookupHistoryRecord? = runCatching {
        val id = json.optString("id").takeIf(String::isNotBlank) ?: return null
        val recordedAt = json.optLong("recordedAt").takeIf { it > 0 } ?: return null
        val mode = LookupMode.valueOf(json.optString("mode"))
        if (json.has("identifier")) {
            LookupHistoryRecord(
                id = id,
                recordedAt = recordedAt,
                mode = mode,
                identifier = json.optString("identifier"),
                model = json.optString("model"),
                marketName = json.optString("marketName"),
                carrierOrCountry = json.optString("carrierOrCountry"),
                retcnForm = json.optJSONObject("retcnForm")?.let(::decodeRetcnForm),
                modelForm = json.optJSONObject("modelForm")?.let(::decodeModelForm),
            )
        } else {
            decodeLegacyRecord(json, id, recordedAt, mode)
        }
    }.getOrNull()

    private fun encodeRetcnForm(form: RetcnForm): JSONObject = JSONObject()
        .put("imei", form.imei).put("serialNumber", form.serialNumber).put("model", form.model)
        .put("carrier", form.carrier).put("fingerprint", form.fingerprint).put("platform", form.platform.name)
        .put("fsgVersion", form.fsgVersion).put("simCount", form.simCount)

    private fun decodeRetcnForm(json: JSONObject): RetcnForm = RetcnForm(
        imei = json.optString("imei"), serialNumber = json.optString("serialNumber"), model = json.optString("model"),
        carrier = json.optString("carrier"), fingerprint = json.optString("fingerprint"),
        platform = Platform.entries.firstOrNull { it.name == json.optString("platform") } ?: Platform.QUALCOMM,
        fsgVersion = json.optString("fsgVersion"), simCount = json.optInt("simCount", 1).coerceIn(1, 2),
    )

    private fun encodeModelForm(form: ModelForm): JSONObject = JSONObject()
        .put("model", form.model).put("category", form.category.name).put("categoryIsAutomatic", form.categoryIsAutomatic)
        .put("countryCode", form.countryCode).put("requiredForModel", form.requiredForModel)
        .put("requiredParameters", JSONArray(form.requiredParameters))
        .put("parameterValues", JSONObject(form.parameterValues))

    private fun decodeModelForm(json: JSONObject): ModelForm {
        val parameterValues = json.optJSONObject("parameterValues") ?: JSONObject()
        return ModelForm(
            model = json.optString("model"),
            category = DeviceCategory.entries.firstOrNull { it.name == json.optString("category") } ?: DeviceCategory.PHONE,
            categoryIsAutomatic = json.optBoolean("categoryIsAutomatic", true),
            countryCode = json.optString("countryCode", "US"),
            requiredForModel = json.optString("requiredForModel"),
            requiredParameters = json.optJSONArray("requiredParameters")?.let { array -> buildList {
                for (index in 0 until array.length()) array.optString(index).takeIf(String::isNotBlank)?.let(::add)
            } }.orEmpty(),
            parameterValues = buildMap {
                val keys = parameterValues.keys()
                while (keys.hasNext()) keys.next().let { key -> put(key, parameterValues.optString(key)) }
            },
        )
    }

    /** Converts records written by the previous result-detail history format. */
    private fun decodeLegacyRecord(json: JSONObject, id: String, recordedAt: Long, mode: LookupMode): LookupHistoryRecord? {
        val query = json.optString("query")
        val data = json.optJSONObject("result")?.optJSONObject("data") ?: return null
        val resultModel = data.optString(if (json.optJSONObject("result")?.optString("type") == "cnTablet") "productModel" else "modelName")
        val identifier = when (mode) {
            LookupMode.ROW_SMARTPHONE, LookupMode.TABLET -> query
            LookupMode.RETCN_SMARTPHONE -> query.substringAfterLast(" · ", "")
            LookupMode.BY_MODEL -> ""
        }
        val model = when (mode) {
            LookupMode.RETCN_SMARTPHONE -> query.substringBeforeLast(" · ", resultModel)
            LookupMode.BY_MODEL -> query
            else -> resultModel
        }
        return LookupHistoryRecord(
            id = id,
            recordedAt = recordedAt,
            mode = mode,
            identifier = identifier,
            model = model,
            marketName = data.optString("marketName"),
            carrierOrCountry = data.optString("carrier"),
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "firmware_lookup_history"
        const val KEY_RECORDS = "records"
    }
}
