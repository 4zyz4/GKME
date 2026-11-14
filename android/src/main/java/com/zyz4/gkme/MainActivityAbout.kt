package com.zyz4.gkme

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

/** GKME 源码页；关于页的徽章按钮和开源项目行都会跳转到浏览器。 */
private const val GKME_SOURCE_URL = "https://github.com/4zyz4/gkme"

/**
 * 关于页展示的一条开源项目。
 *
 * 项目名保持英文原名（[name]），用途来自 [descriptionRes]，点击后打开 [url]。
 */
private data class AboutProject(
    val name: String,
    val descriptionRes: Int,
    val url: String,
)

/** README「鸣谢 → 参考与衍生项目」。 */
private val referencedProjects = listOf(
    AboutProject(
        "HIDMaestro",
        R.string.i18n_about_project_hidmaestro_desc,
        "https://github.com/hifihedgehog/HIDMaestro",
    ),
    AboutProject(
        "usbip-win2",
        R.string.i18n_about_project_usbip_desc,
        "https://github.com/vadimgrn/usbip-win2",
    ),
    AboutProject(
        "Moonlight",
        R.string.i18n_about_project_moonlight_desc,
        "https://github.com/moonlight-stream/moonlight-android",
    ),
    AboutProject(
        "Axixi2233/moonlight-android",
        R.string.i18n_about_project_axixi_desc,
        "https://github.com/Axixi2233/moonlight-android",
    ),
    AboutProject(
        "qiin2333/moonlight-vplus",
        R.string.i18n_about_project_vplus_desc,
        "https://github.com/qiin2333/moonlight-vplus",
    ),
)

/** README「鸣谢 → 使用的开源库」，与 README 保持一致。 */
private val openSourceLibraries = listOf(
    AboutProject(
        "SDL3 / HIDAPI",
        R.string.i18n_about_project_sdl3_desc,
        "https://libsdl.org/",
    ),
    AboutProject(
        "Dagger Hilt",
        R.string.i18n_about_project_hilt_desc,
        "https://dagger.dev/hilt/",
    ),
    AboutProject(
        "Shizuku",
        R.string.i18n_about_project_shizuku_desc,
        "https://shizuku.rikka.app/",
    ),
)

internal fun MainActivity.setupAboutPage() {
    val a = this
    val packageInfo = a.packageManager.getPackageInfo(a.packageName, 0)

    a.findViewById<ImageView>(R.id.ivAppIcon).setImageResource(R.mipmap.icon)
    a.findViewById<TextView>(R.id.tvAppName).text = getString(R.string.app_name)
    a.findViewById<TextView>(R.id.tvAppVersion).text =
        getString(R.string.i18n_version_0, packageInfo.versionName)
    a.findViewById<TextView>(R.id.tvAppDescription).text = getString(R.string.i18n_about_intro)
    // 复用的作者文案带尾部空行，放进卡片前去掉，避免正文与协议之间出现大块留白。
    a.findViewById<TextView>(R.id.tvAboutAuthor).text =
        getString(R.string.i18n_author_4zyz4_qq_group_639317971).trim()

    a.findViewById<View>(R.id.btnAboutSource).setOnClickListener {
        a.openExternalUrl(GKME_SOURCE_URL)
    }
    a.findViewById<View>(R.id.btnAboutLicense).setOnClickListener {
        a.showGplLicenseDialog()
    }
    a.findViewById<View>(R.id.btnSponsor).setOnClickListener {
        a.showSponsorDialog()
    }

    a.bindAboutProjects(R.id.layoutReferencedProjects, referencedProjects)
    a.bindAboutProjects(R.id.layoutOpenSourceLibraries, openSourceLibraries)
}

/** 用代码生成开源项目行，避免在 XML 里重复八份相同结构。 */
private fun MainActivity.bindAboutProjects(containerId: Int, projects: List<AboutProject>) {
    val a = this
    val container = a.findViewById<LinearLayout>(containerId) ?: return
    container.removeAllViews()

    val density = a.resources.displayMetrics.density
    fun dp(value: Int): Int = (value * density).toInt()
    val primary = ContextCompat.getColor(a, R.color.text_primary)
    val secondary = ContextCompat.getColor(a, R.color.text_secondary)
    val dividerColor = ContextCompat.getColor(a, R.color.bg_surface_light)
    val rowBackground = ContextCompat.getDrawable(a, R.drawable.bg_about_project_row)

    projects.forEachIndexed { index, project ->
        if (index > 0) {
            container.addView(
                View(a).apply { setBackgroundColor(dividerColor) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
            )
        }

        val name = TextView(a).apply {
            text = project.name
            textSize = 14f
            setTextColor(primary)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val arrow = TextView(a).apply {
            text = "↗"
            textSize = 13f
            setTextColor(secondary)
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleRow = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                name,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                arrow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(8) },
            )
        }

        val description = TextView(a).apply {
            text = a.getString(project.descriptionRes)
            textSize = 12f
            setTextColor(secondary)
            setLineSpacing(dp(1).toFloat(), 1f)
        }

        val row = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rowBackground
            isClickable = true
            isFocusable = true
            addView(titleRow)
            addView(
                description,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(2) },
            )
            setOnClickListener { a.openExternalUrl(project.url) }
        }
        container.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }
}

/** 打开外部链接；设备没有可用浏览器时给出提示，而不是崩溃。 */
internal fun MainActivity.openExternalUrl(url: String) {
    val opened = runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.isSuccess
    if (!opened) {
        CustomDialog.showToast(this, getString(R.string.i18n_about_unable_to_open_link))
    }
}

/** 关于页的 GPLv3 徽章：在对话框内展示仓库自带的协议全文。 */
internal fun MainActivity.showGplLicenseDialog() {
    val a = this
    val text = TextView(a).apply {
        text = a.readGplLicenseText()
        textSize = 12f
        setTextColor(ContextCompat.getColor(a, R.color.text_secondary))
        typeface = Typeface.MONOSPACE
        setLineSpacing(0f, 1.15f)
    }
    val scroll = ScrollView(a).apply {
        isVerticalScrollBarEnabled = true
        addView(
            text,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }
    CustomDialog.showCustomView(
        a,
        getString(R.string.i18n_gpl3_full_title),
        scroll,
        negativeText = getString(R.string.i18n_close),
        scrollable = true,
    )
}

internal fun MainActivity.showSponsorDialog() {
    val a = this
    val imageView = ImageView(a).apply {
        setImageResource(R.mipmap.reward)
        setScaleType(ImageView.ScaleType.FIT_CENTER)
        setPadding(40, 0, 40, 0)
    }
    val content = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(a).apply {
            text = getString(R.string.i18n_thank_you_for_your_support)
            textSize = 14f
            setTextColor(-0x777778)
            gravity = Gravity.CENTER
        })
        addView(imageView)
    }
    CustomDialog.showCustomView(
        a,
        getString(R.string.i18n_sponsor),
        content,
        negativeText = getString(R.string.i18n_close),
    )
}
