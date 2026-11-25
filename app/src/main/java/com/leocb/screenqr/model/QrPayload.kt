package com.leocb.screenqr.model

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import com.leocb.screenqr.R
import java.util.Locale

/**
 * A decoded QR payload, classified into something the UI can present with sensible actions.
 *
 * Only the raw string is passed between components; [parse] is cheap and re-run where needed.
 * Everything user-visible is a string resource so the UI follows the device/app language.
 */
data class QrPayload(
    val raw: String,
    val kind: Kind,
    /** Short description of what this code is, e.g. "Wi-Fi network". */
    @param:StringRes val headlineRes: Int,
    /** The main human-readable value, e.g. the URL or the network name. May be blank. */
    val value: String,
    val fields: List<Field> = emptyList(),
    val openIntent: Intent? = null,
    @param:StringRes val openLabelRes: Int? = null,
    /** Where to go if no installed app accepts [openIntent] (intent:// browser fallback). */
    val fallbackUrl: String? = null,
    @param:StringRes val noteRes: Int? = null,
    /** Single format argument for [noteRes]. */
    val noteArg: String? = null,
    /** Shown when [value] is blank. */
    @param:StringRes val valueFallbackRes: Int? = null,
    /** Offer "Open with…" so another installed app (bank, wallet, store) can take it. */
    val allowChooser: Boolean = openIntent != null,
) {
    data class Field(
        @param:StringRes val labelRes: Int,
        val value: String,
        val sensitive: Boolean = false,
        /** When set, replaces [value] in the UI (for fixed words such as "Yes"). */
        @param:StringRes val valueRes: Int? = null,
    )

    enum class Kind(@param:StringRes val labelRes: Int) {
        LINK(R.string.kind_link),
        WIFI(R.string.kind_wifi),
        PHONE(R.string.kind_phone),
        EMAIL(R.string.kind_email),
        SMS(R.string.kind_sms),
        LOCATION(R.string.kind_location),
        CONTACT(R.string.kind_contact),
        OTP(R.string.kind_otp),
        APP(R.string.kind_app),
        PAYMENT(R.string.kind_payment),
        TEXT(R.string.kind_text),
    }

    fun resolvedValue(context: Context): String =
        value.ifBlank { context.getString(valueFallbackRes ?: R.string.value_no_name) }

    fun resolvedNote(context: Context): String? = noteRes?.let { res ->
        if (noteArg != null) context.getString(res, noteArg) else context.getString(res)
    }

    companion object {

        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")

        private val CRYPTO_SCHEMES = mapOf(
            "bitcoin" to R.string.headline_bitcoin,
            "ethereum" to R.string.headline_ethereum,
            "solana" to R.string.headline_solana,
            "litecoin" to R.string.headline_litecoin,
            "dogecoin" to R.string.headline_dogecoin,
            "monero" to R.string.headline_monero,
            "dash" to R.string.headline_dash,
            "zcash" to R.string.headline_zcash,
            "lightning" to R.string.headline_lightning,
        )

        private val CURRENCIES = mapOf(
            "986" to "BRL",
            "840" to "USD",
            "978" to "EUR",
            "826" to "GBP",
            "356" to "INR",
            "036" to "AUD",
            "124" to "CAD",
        )

        fun parse(input: String): QrPayload {
            val raw = input.trim()
            val lower = raw.lowercase(Locale.ROOT)
            return when {
                lower.startsWith("wifi:") -> wifi(raw)
                lower.startsWith("begin:vcard") -> vcard(raw)
                lower.startsWith("mecard:") -> mecard(raw)
                lower.startsWith("otpauth://") || lower.startsWith("otpauth-migration://") ->
                    QrPayload(
                        raw = raw,
                        kind = Kind.OTP,
                        headlineRes = R.string.headline_otp,
                        value = raw.substringBefore('?'),
                        noteRes = R.string.note_otp,
                    )
                lower.startsWith("tel:") -> QrPayload(
                    raw = raw,
                    kind = Kind.PHONE,
                    headlineRes = R.string.headline_phone,
                    value = raw.removePrefix("tel:").removePrefix("TEL:"),
                    openIntent = view(raw),
                    openLabelRes = R.string.label_dial,
                )
                lower.startsWith("mailto:") -> QrPayload(
                    raw = raw,
                    kind = Kind.EMAIL,
                    headlineRes = R.string.headline_email,
                    value = raw.removePrefix("mailto:").removePrefix("MAILTO:").substringBefore('?'),
                    openIntent = sendTo(raw),
                    openLabelRes = R.string.label_compose,
                )
                lower.startsWith("smsto:") || lower.startsWith("sms:") || lower.startsWith("mms:") ||
                    lower.startsWith("mmsto:") -> sms(raw)
                lower.startsWith("geo:") -> QrPayload(
                    raw = raw,
                    kind = Kind.LOCATION,
                    headlineRes = R.string.headline_location,
                    value = raw.removePrefix("geo:").replace(",", ", ").substringBefore('?'),
                    openIntent = view(raw),
                    openLabelRes = R.string.label_open_map,
                )
                lower.startsWith("upi:") -> upi(raw)
                // EMVCo merchant-presented QR: Pix (Brazil), SGQR, PromptPay, ...
                lower.startsWith("000201") -> emv(raw)
                CRYPTO_SCHEMES.keys.any { lower.startsWith("$it:") } -> crypto(raw, lower)
                lower.startsWith("http://") || lower.startsWith("https://") -> QrPayload(
                    raw = raw,
                    kind = Kind.LINK,
                    headlineRes = R.string.headline_web_link,
                    value = raw,
                    openIntent = view(raw),
                    openLabelRes = R.string.label_open_link,
                )
                lower.startsWith("intent:") -> intentUri(raw)
                lower.startsWith("market://") -> QrPayload(
                    raw = raw,
                    kind = Kind.APP,
                    headlineRes = R.string.headline_play_store,
                    value = raw,
                    openIntent = view(raw),
                    openLabelRes = R.string.label_open_in_store,
                )
                SCHEME.containsMatchIn(raw.substringBefore(' ')) -> QrPayload(
                    raw = raw,
                    kind = Kind.APP,
                    headlineRes = R.string.headline_app_link,
                    value = raw,
                    openIntent = view(raw),
                    openLabelRes = R.string.label_open,
                )
                else -> QrPayload(
                    raw = raw,
                    kind = Kind.TEXT,
                    headlineRes = R.string.headline_text,
                    value = raw,
                )
            }
        }

        private fun view(uri: String): Intent? = runCatching {
            Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        }.getOrNull()

        private fun sendTo(uri: String): Intent? = runCatching {
            Intent(Intent.ACTION_SENDTO, Uri.parse(uri))
        }.getOrNull()

        /**
         * `intent://host/path#Intent;scheme=https;package=com.example;S.browser_fallback_url=…;end`
         * The fallback URL matters: when the target app is not installed Android throws, and the
         * sensible thing is to open the web page instead.
         */
        private fun intentUri(raw: String): QrPayload {
            val parsed = runCatching { Intent.parseUri(raw, Intent.URI_INTENT_SCHEME) }.getOrNull()
            val fallback = parsed?.getStringExtra("browser_fallback_url")
                ?: Regex("S\\.browser_fallback_url=([^;]+)").find(raw)?.groupValues?.get(1)
            return QrPayload(
                raw = raw,
                kind = Kind.APP,
                headlineRes = R.string.headline_app_link,
                value = parsed?.dataString ?: raw,
                openIntent = parsed,
                openLabelRes = R.string.label_open_in_app,
                fallbackUrl = fallback,
                allowChooser = true,
            )
        }

        private fun upi(raw: String): QrPayload {
            val uri = runCatching { Uri.parse(raw) }.getOrNull()
            val payeeName = uri?.getQueryParameter("pn")
            val address = uri?.getQueryParameter("pa")
            val amount = uri?.getQueryParameter("am")
            val currency = uri?.getQueryParameter("cu")
            val note = uri?.getQueryParameter("tn")
            return QrPayload(
                raw = raw,
                kind = Kind.PAYMENT,
                headlineRes = R.string.headline_upi,
                value = payeeName ?: address ?: "",
                valueFallbackRes = R.string.value_payment_code,
                fields = buildList {
                    address?.takeIf { it.isNotBlank() }
                        ?.let { add(Field(R.string.field_payee_id, it)) }
                    amount?.takeIf { it.isNotBlank() }?.let {
                        add(Field(R.string.field_amount, if (currency.isNullOrBlank()) it else "$it $currency"))
                    }
                    note?.takeIf { it.isNotBlank() }?.let { add(Field(R.string.field_note, it)) }
                },
                openIntent = view(raw),
                openLabelRes = R.string.label_pay_with_app,
                noteRes = R.string.note_upi,
                allowChooser = true,
            )
        }

        /** Pix and friends: EMVCo TLV, not a URI, so there is nothing to deep link to. */
        private fun emv(raw: String): QrPayload {
            val top = parseTlv(raw)
            val isPix = raw.contains("br.gov.bcb.pix", ignoreCase = true)

            var pixGui: String? = null
            var pixKey: String? = null
            var pixDescription: String? = null
            if (top != null) {
                for (tag in 26..51) {
                    val account = top[tag.toString()] ?: continue
                    val sub = parseTlv(account) ?: continue
                    pixGui = sub["00"] ?: pixGui
                    pixKey = sub["01"] ?: pixKey
                    pixDescription = sub["02"] ?: pixDescription
                }
            }

            val merchant = top?.get("59")
            val city = top?.get("60")
            val amount = top?.get("54")
            val currency = top?.get("53")?.let { CURRENCIES[it] ?: it }

            return QrPayload(
                raw = raw,
                kind = Kind.PAYMENT,
                headlineRes = if (isPix) R.string.headline_pix else R.string.headline_payment_code,
                value = merchant?.takeIf { it.isNotBlank() } ?: pixKey?.takeIf { it.isNotBlank() } ?: "",
                valueFallbackRes = if (isPix) R.string.value_pix_code else R.string.value_payment_code,
                fields = buildList {
                    pixKey?.takeIf { it.isNotBlank() }?.let { add(Field(R.string.field_pix_key, it)) }
                    pixDescription?.takeIf { it.isNotBlank() }
                        ?.let { add(Field(R.string.field_description, it)) }
                    merchant?.takeIf { it.isNotBlank() }?.let { add(Field(R.string.field_merchant, it)) }
                    city?.takeIf { it.isNotBlank() }?.let { add(Field(R.string.field_city, it)) }
                    amount?.takeIf { it.isNotBlank() }?.let {
                        add(Field(R.string.field_amount, if (currency == null) it else "$it $currency"))
                    }
                    pixGui?.takeIf { it.isNotBlank() && !it.startsWith("br.gov.bcb") }
                        ?.let { add(Field(R.string.field_scheme, it)) }
                },
                noteRes = if (isPix) R.string.note_pix else R.string.note_payment,
            )
        }

        private fun crypto(raw: String, lower: String): QrPayload {
            val scheme = lower.substringBefore(':')
            val uri = runCatching { Uri.parse(raw) }.getOrNull()
            val address = raw.substringAfter(':').substringBefore('?')
            return QrPayload(
                raw = raw,
                kind = Kind.PAYMENT,
                headlineRes = CRYPTO_SCHEMES[scheme] ?: R.string.headline_crypto,
                value = address.ifBlank { raw },
                fields = buildList {
                    uri?.getQueryParameter("amount")?.takeIf { it.isNotBlank() }
                        ?.let { add(Field(R.string.field_amount, it)) }
                    uri?.getQueryParameter("label")?.takeIf { it.isNotBlank() }
                        ?.let { add(Field(R.string.field_label, it)) }
                    uri?.getQueryParameter("message")?.takeIf { it.isNotBlank() }
                        ?.let { add(Field(R.string.field_message, it)) }
                },
                openIntent = view(raw),
                openLabelRes = R.string.label_open_wallet,
                noteRes = R.string.note_crypto,
                noteArg = scheme,
                allowChooser = true,
            )
        }

        /** Reads a flat EMVCo TLV block: 2-digit tag, 2-digit length, value. */
        private fun parseTlv(data: String): Map<String, String>? {
            val out = LinkedHashMap<String, String>()
            var index = 0
            while (index + 4 <= data.length) {
                val tag = data.substring(index, index + 2)
                if (!tag.all(Char::isDigit)) break
                val length = data.substring(index + 2, index + 4).toIntOrNull() ?: break
                index += 4
                if (length < 0 || index + length > data.length) break
                out[tag] = data.substring(index, index + length)
                index += length
            }
            return out.ifEmpty { null }
        }

        private fun sms(raw: String): QrPayload {
            val body = raw.substringAfter(':')
            val number = body.substringBefore(':').substringBefore('?')
            val text = when {
                body.contains(':') -> body.substringAfter(':')
                body.contains("body=") -> Uri.parse(raw).getQueryParameter("body").orEmpty()
                else -> ""
            }
            return QrPayload(
                raw = raw,
                kind = Kind.SMS,
                headlineRes = R.string.headline_sms,
                value = number,
                fields = if (text.isNotBlank()) listOf(Field(R.string.field_message, text)) else emptyList(),
                openIntent = when {
                    text.isNotBlank() -> view("smsto:$number?body=${Uri.encode(text)}")
                    else -> view("smsto:$number")
                },
                openLabelRes = R.string.label_open_messages,
            )
        }

        private fun wifi(raw: String): QrPayload {
            val map = parseEscapedFields(raw.substringAfter(':'))
            val ssid = map["S"].orEmpty()
            val password = map["P"].orEmpty()
            val type = map["T"].orEmpty()
            val hidden = map["H"].equals("true", ignoreCase = true)
            return QrPayload(
                raw = raw,
                kind = Kind.WIFI,
                headlineRes = R.string.headline_wifi,
                value = ssid,
                valueFallbackRes = R.string.value_no_name,
                fields = buildList {
                    add(Field(R.string.field_network, ssid))
                    if (type.isNotBlank()) add(Field(R.string.field_security, type.uppercase(Locale.ROOT)))
                    if (password.isNotBlank()) add(Field(R.string.field_password, password, sensitive = true))
                    if (hidden) add(Field(R.string.field_hidden, "", valueRes = R.string.value_yes))
                },
                noteRes = R.string.note_wifi,
            )
        }

        /** Parses `S:ssid;T:WPA;P:secret\;word;;` style payloads with backslash escapes. */
        private fun parseEscapedFields(body: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            var key: String? = null
            val value = StringBuilder()
            var index = 0
            while (index < body.length) {
                val c = body[index]
                when {
                    c == '\\' && index + 1 < body.length -> {
                        value.append(body[index + 1])
                        index++
                    }
                    c == ':' && key == null -> {
                        key = value.toString().trim().uppercase(Locale.ROOT)
                        value.clear()
                    }
                    c == ';' -> {
                        if (key != null) result[key] = value.toString()
                        key = null
                        value.clear()
                    }
                    else -> value.append(c)
                }
                index++
            }
            if (key != null) result[key] = value.toString()
            return result
        }

        private fun mecard(raw: String): QrPayload {
            val map = parseEscapedFields(raw.substringAfter(':'))
            val name = map["N"].orEmpty().replace("\\,", ",")
            return QrPayload(
                raw = raw,
                kind = Kind.CONTACT,
                headlineRes = R.string.headline_contact,
                value = name,
                valueFallbackRes = R.string.value_unnamed_contact,
                fields = buildList {
                    map["TEL"]?.takeIf { it.isNotBlank() }?.let { add(Field(R.string.field_phone, it)) }
                    map["EMAIL"]?.takeIf { it.isNotBlank() }?.let { add(Field(R.string.field_email, it)) }
                    map["ADR"]?.takeIf { it.isNotBlank() }?.let { add(Field(R.string.field_address, it)) }
                },
            )
        }

        private fun vcard(raw: String): QrPayload {
            fun prop(name: String): String? = raw.lineSequence()
                .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            val fullName = prop("FN") ?: prop("N")
                ?.split(';')
                ?.filter { it.isNotBlank() }
                ?.reversed()
                ?.joinToString(" ")
            return QrPayload(
                raw = raw,
                kind = Kind.CONTACT,
                headlineRes = R.string.headline_contact,
                value = fullName.orEmpty(),
                valueFallbackRes = R.string.value_unnamed_contact,
                fields = buildList {
                    prop("TEL")?.let { add(Field(R.string.field_phone, it)) }
                    prop("EMAIL")?.let { add(Field(R.string.field_email, it)) }
                    prop("ORG")?.let { add(Field(R.string.field_organisation, it)) }
                    prop("TITLE")?.let { add(Field(R.string.field_title, it)) }
                },
            )
        }
    }
}
