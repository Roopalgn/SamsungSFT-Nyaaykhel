package com.nyaaykhel.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature

/**
 * Android Keystore signing for NyaayKhel tamper-evident match records.
 *
 * Generates an RSA-2048 keypair inside the Android Keystore (hardware-backed
 * on most modern devices). The private key never leaves secure hardware.
 *
 * At export time:
 *   1. [getOrCreatePublicKey] returns the Base64 public key for embedding in JSON.
 *   2. [sign] signs the terminal hash of the event chain with the private key.
 *   3. Verifiers can check the signature using the embedded public key.
 *
 * This is deliberately scoped: we sign the terminal hash of the hash chain,
 * which is the SHA-256 of the last event's data (which includes all prior
 * events through the chain). Altering any event breaks the chain, and the
 * terminal hash no longer matches the signature. That's the full claim.
 */
object KeystoreSigner {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "NyaayKhelMatchSigningKey"

    /**
     * Return the Base64-encoded DER public key, creating the keypair if it
     * doesn't already exist in the Keystore.
     */
    fun getOrCreatePublicKey(): String {
        if (!keyExists()) {
            generateKeyPair()
        }
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    /**
     * Sign [data] (the terminal hash hex string) with the Keystore private key.
     * Returns Base64-encoded signature bytes.
     */
    fun sign(data: String): String {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null)
            ?: error("Signing key not found — call getOrCreatePublicKey() first")

        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(data.toByteArray(Charsets.UTF_8))
        }.sign()

        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    private fun keyExists(): Boolean {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.containsAlias(KEY_ALIAS)
    }

    private fun generateKeyPair() {
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        ).apply {
            setKeySize(2048)
            setDigests(KeyProperties.DIGEST_SHA256)
            setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            // Don't require user authentication — this is device-level signing,
            // not user-interaction signing. We want it to work in background.
            setUserAuthenticationRequired(false)
        }.build()

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE_PROVIDER).apply {
            initialize(spec)
            generateKeyPair()
        }
    }
}
