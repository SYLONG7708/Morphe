/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.viewmodel

import androidx.lifecycle.ViewModel
import app.morphe.manager.ui.screen.shared.autoMirrored
import app.morphe.manager.util.MORPHE_WEBSITE_URL
import com.composables.icons.fontawesome.FontAwesome
import com.composables.icons.fontawesome.brands.*
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.*

data class SocialLink(
    val name: String,
    val url: String,
    val preferred: Boolean = false,
)

class AboutViewModel : ViewModel() {
    companion object {
        val socials: List<SocialLink> = listOf(
            SocialLink(
                name = "Website",
                url = MORPHE_WEBSITE_URL,
                preferred = true
            ),
            SocialLink(
                name = "Changelog",
                url = "$MORPHE_WEBSITE_URL/changelog"
            ),
            SocialLink(
                name = "GitHub",
                url = "https://github.com/MorpheApp"
            ),
            SocialLink(
                name = "Reddit",
                url = "https://reddit.com/r/MorpheApp"
            ),
            SocialLink(
                name = "Crowdin",
                url = "$MORPHE_WEBSITE_URL/translate"
            )
        )

        private val socialIcons = mapOf(
            "Website" to MaterialSymbols.Rounded.Public,
            "GitHub" to FontAwesome.Brands.Github,
            "Changelog" to MaterialSymbols.Rounded.Article.autoMirrored,
            "Reddit" to FontAwesome.Brands.Reddit,
            "Crowdin" to MaterialSymbols.Rounded.Translate,
        )

        fun getSocialIcon(name: String) = socialIcons[name] ?: MaterialSymbols.Rounded.Language
    }
}
