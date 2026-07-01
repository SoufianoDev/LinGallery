package com.soufianodev.lingallery.app

import java.net.URLEncoder

object IssueReporter {
    private const val REPO_URL = "https://github.com/SoufianoDev/LinGallery/issues/new"

    fun buildUrl(title: String, body: String, labels: String = "bug"): String {
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val encodedBody = URLEncoder.encode(body, "UTF-8")
        val encodedLabels = URLEncoder.encode(labels, "UTF-8")
        return "$REPO_URL?title=$encodedTitle&body=$encodedBody&labels=$encodedLabels"
    }

    fun open(title: String, body: String, labels: String = "bug") {
        java.awt.Desktop.getDesktop().browse(java.net.URI(buildUrl(title, body, labels)))
    }
}
