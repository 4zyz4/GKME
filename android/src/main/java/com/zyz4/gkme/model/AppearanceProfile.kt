package com.zyz4.gkme.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

data class AppearanceProfile(
    @SerializedName("version")
    val version: Int = 1,

    // ── Appearance fields (subset of AppSettings) ──
    @SerializedName("bgFillType")
    val bgFillType: Int = 0,

    @SerializedName("bgColor")
    val bgColor: Int = -0x1000000,

    @SerializedName("bgImagePath")
    val bgImagePath: String? = null,

    @SerializedName("btnFillType")
    val btnFillType: Int = 0,

    @SerializedName("btnColor")
    val btnColor: Int = -0x474748,

    @SerializedName("btnImagePath")
    val btnImagePath: String? = null,

    @SerializedName("btnOutlineColor")
    val btnOutlineColor: Int = -0x99999a,

    @SerializedName("btnOutlineWidth")
    val btnOutlineWidth: Int = 4,

    @SerializedName("joyBaseFillType")
    val joyBaseFillType: Int = 0,

    @SerializedName("joyBaseColor")
    val joyBaseColor: Int = -0xdddddd,

    @SerializedName("joyBaseImagePath")
    val joyBaseImagePath: String? = null,

    @SerializedName("joyBaseOutlineColor")
    val joyBaseOutlineColor: Int = -0xaaaaab,

    @SerializedName("joyBaseOutlineWidth")
    val joyBaseOutlineWidth: Int = 4,

    @SerializedName("joyCapFillType")
    val joyCapFillType: Int = 0,

    @SerializedName("joyCapColor")
    val joyCapColor: Int = -0xaaaaab,

    @SerializedName("joyCapImagePath")
    val joyCapImagePath: String? = null,

    @SerializedName("joyCapOutlineColor")
    val joyCapOutlineColor: Int = -0x888889,

    @SerializedName("joyCapOutlineWidth")
    val joyCapOutlineWidth: Int = 4,

    @SerializedName("joyTriggerOutlineColor")
    val joyTriggerOutlineColor: Int = -0x666667,

    @SerializedName("joyTriggerOutlineWidth")
    val joyTriggerOutlineWidth: Int = 4,

    @SerializedName("tpTriggerOutlineColor")
    val tpTriggerOutlineColor: Int = -0x666667,

    @SerializedName("tpTriggerOutlineWidth")
    val tpTriggerOutlineWidth: Int = 4,

    @SerializedName("linearTriggerBoxOutlineColor")
    val linearTriggerBoxOutlineColor: Int = -0x777778,

    @SerializedName("linearTriggerBoxOutlineWidth")
    val linearTriggerBoxOutlineWidth: Int = 4,

    @SerializedName("tpFillType")
    val tpFillType: Int = 0,

    @SerializedName("tpColor")
    val tpColor: Int = -0xededed,

    @SerializedName("tpImagePath")
    val tpImagePath: String? = null,

    @SerializedName("tpOutlineColor")
    val tpOutlineColor: Int = -0x99999a,

    @SerializedName("tpOutlineWidth")
    val tpOutlineWidth: Int = 4,

    @SerializedName("dpadPadFillType")
    val dpadPadFillType: Int = 0,

    @SerializedName("dpadPadColor")
    val dpadPadColor: Int = -0x474748,

    @SerializedName("dpadPadImagePath")
    val dpadPadImagePath: String? = null,

    @SerializedName("dpadPadOutlineColor")
    val dpadPadOutlineColor: Int = -0x99999a,

    @SerializedName("dpadPadOutlineWidth")
    val dpadPadOutlineWidth: Int = 4,

    @SerializedName("dpadPadTriggerOutlineColor")
    val dpadPadTriggerOutlineColor: Int = -0x666667,

    @SerializedName("dpadPadTriggerOutlineWidth")
    val dpadPadTriggerOutlineWidth: Int = 4,

    @SerializedName("iconMaxSize")
    val iconMaxSize: Int = 24,
) {
    companion object {
        private val gson = Gson()

        fun fromAppSettings(settings: com.zyz4.gkme.model.AppSettings): AppearanceProfile {
            return AppearanceProfile(
                bgFillType = settings.bgFillType.ordinal,
                bgColor = settings.bgColor,
                bgImagePath = settings.bgImagePath,
                btnFillType = settings.btnFillType.ordinal,
                btnColor = settings.btnColor,
                btnImagePath = settings.btnImagePath,
                btnOutlineColor = settings.btnOutlineColor,
                btnOutlineWidth = settings.btnOutlineWidth,
                joyBaseFillType = settings.joyBaseFillType.ordinal,
                joyBaseColor = settings.joyBaseColor,
                joyBaseImagePath = settings.joyBaseImagePath,
                joyBaseOutlineColor = settings.joyBaseOutlineColor,
                joyBaseOutlineWidth = settings.joyBaseOutlineWidth,
                joyCapFillType = settings.joyCapFillType.ordinal,
                joyCapColor = settings.joyCapColor,
                joyCapImagePath = settings.joyCapImagePath,
                joyCapOutlineColor = settings.joyCapOutlineColor,
                joyCapOutlineWidth = settings.joyCapOutlineWidth,
                joyTriggerOutlineColor = settings.joyTriggerOutlineColor,
                joyTriggerOutlineWidth = settings.joyTriggerOutlineWidth,
                tpTriggerOutlineColor = settings.tpTriggerOutlineColor,
                tpTriggerOutlineWidth = settings.tpTriggerOutlineWidth,
                linearTriggerBoxOutlineColor = settings.linearTriggerBoxOutlineColor,
                linearTriggerBoxOutlineWidth = settings.linearTriggerBoxOutlineWidth,
                tpFillType = settings.tpFillType.ordinal,
                tpColor = settings.tpColor,
                tpImagePath = settings.tpImagePath,
                tpOutlineColor = settings.tpOutlineColor,
                tpOutlineWidth = settings.tpOutlineWidth,
                dpadPadFillType = settings.dpadPadFillType.ordinal,
                dpadPadColor = settings.dpadPadColor,
                dpadPadImagePath = settings.dpadPadImagePath,
                dpadPadOutlineColor = settings.dpadPadOutlineColor,
                dpadPadOutlineWidth = settings.dpadPadOutlineWidth,
                dpadPadTriggerOutlineColor = settings.dpadPadTriggerOutlineColor,
                dpadPadTriggerOutlineWidth = settings.dpadPadTriggerOutlineWidth,
                iconMaxSize = settings.iconMaxSize,
            )
        }

        fun fromAppSettingsWithImageNames(
            settings: com.zyz4.gkme.model.AppSettings,
            imageNames: Map<String, String>
        ): AppearanceProfile {
            return AppearanceProfile(
                bgFillType = settings.bgFillType.ordinal,
                bgColor = settings.bgColor,
                bgImagePath = settings.bgImagePath?.let { imageNames[it] },
                btnFillType = settings.btnFillType.ordinal,
                btnColor = settings.btnColor,
                btnImagePath = settings.btnImagePath?.let { imageNames[it] },
                btnOutlineColor = settings.btnOutlineColor,
                btnOutlineWidth = settings.btnOutlineWidth,
                joyBaseFillType = settings.joyBaseFillType.ordinal,
                joyBaseColor = settings.joyBaseColor,
                joyBaseImagePath = settings.joyBaseImagePath?.let { imageNames[it] },
                joyBaseOutlineColor = settings.joyBaseOutlineColor,
                joyBaseOutlineWidth = settings.joyBaseOutlineWidth,
                joyCapFillType = settings.joyCapFillType.ordinal,
                joyCapColor = settings.joyCapColor,
                joyCapImagePath = settings.joyCapImagePath?.let { imageNames[it] },
                joyCapOutlineColor = settings.joyCapOutlineColor,
                joyCapOutlineWidth = settings.joyCapOutlineWidth,
                joyTriggerOutlineColor = settings.joyTriggerOutlineColor,
                joyTriggerOutlineWidth = settings.joyTriggerOutlineWidth,
                tpTriggerOutlineColor = settings.tpTriggerOutlineColor,
                tpTriggerOutlineWidth = settings.tpTriggerOutlineWidth,
                linearTriggerBoxOutlineColor = settings.linearTriggerBoxOutlineColor,
                linearTriggerBoxOutlineWidth = settings.linearTriggerBoxOutlineWidth,
                tpFillType = settings.tpFillType.ordinal,
                tpColor = settings.tpColor,
                tpImagePath = settings.tpImagePath?.let { imageNames[it] },
                tpOutlineColor = settings.tpOutlineColor,
                tpOutlineWidth = settings.tpOutlineWidth,
                dpadPadFillType = settings.dpadPadFillType.ordinal,
                dpadPadColor = settings.dpadPadColor,
                dpadPadImagePath = settings.dpadPadImagePath?.let { imageNames[it] },
                dpadPadOutlineColor = settings.dpadPadOutlineColor,
                dpadPadOutlineWidth = settings.dpadPadOutlineWidth,
                dpadPadTriggerOutlineColor = settings.dpadPadTriggerOutlineColor,
                dpadPadTriggerOutlineWidth = settings.dpadPadTriggerOutlineWidth,
                iconMaxSize = settings.iconMaxSize,
            )
        }

        fun toJson(profile: AppearanceProfile): String {
            return gson.toJson(profile)
        }

        fun fromJson(json: String): AppearanceProfile {
            return gson.fromJson(json, AppearanceProfile::class.java)
        }
    }
}