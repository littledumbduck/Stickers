package com.littledumbduck.stickers.whatsapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.littledumbduck.stickers.BuildConfig

/** Comunicación con la app de WhatsApp (normal y Business). */
object WhatsAppIntegration {

    const val CONSUMER = "com.whatsapp"
    const val BUSINESS = "com.whatsapp.w4b"

    private const val ACTION_ENABLE_STICKER_PACK = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
    private const val EXTRA_PACK_ID = "sticker_pack_id"
    private const val EXTRA_PACK_AUTHORITY = "sticker_pack_authority"
    private const val EXTRA_PACK_NAME = "sticker_pack_name"
    const val EXTRA_VALIDATION_ERROR = "validation_error"

    fun label(pkg: String) = if (pkg == BUSINESS) "WhatsApp Business" else "WhatsApp"

    fun installedApps(context: Context): List<String> =
        listOf(CONSUMER, BUSINESS).filter { isInstalled(context, it) }

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Intent que abre el diálogo de WhatsApp para añadir (o actualizar) el paquete. */
    fun addPackIntent(identifier: String, name: String, pkg: String): Intent =
        Intent(ACTION_ENABLE_STICKER_PACK).apply {
            putExtra(EXTRA_PACK_ID, identifier)
            putExtra(EXTRA_PACK_AUTHORITY, BuildConfig.CONTENT_PROVIDER_AUTHORITY)
            putExtra(EXTRA_PACK_NAME, name)
            setPackage(pkg)
        }

    /** Indica si el paquete ya está añadido en la app de WhatsApp indicada. */
    fun isPackAdded(context: Context, identifier: String, pkg: String): Boolean = runCatching {
        val uri = Uri.Builder()
            .scheme("content")
            .authority("$pkg.provider.sticker_whitelist_check")
            .appendPath("is_whitelisted")
            .appendQueryParameter("authority", BuildConfig.CONTENT_PROVIDER_AUTHORITY)
            .appendQueryParameter("identifier", identifier)
            .build()
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow("result")) == 1
        } ?: false
    }.getOrDefault(false)

    /** Apps de WhatsApp instaladas en las que el paquete ya está añadido. */
    fun appsWithPack(context: Context, identifier: String): Set<String> =
        installedApps(context).filter { isPackAdded(context, identifier, it) }.toSet()
}
