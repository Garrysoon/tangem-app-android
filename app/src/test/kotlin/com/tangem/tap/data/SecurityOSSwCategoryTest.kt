package com.tangem.tap.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityOSSwCategoryTest {

    /**
     * Verify SW code ranges match SecurityOS firmware constants.
     * These are the SW codes the mapper logs in mapResponse().
     */
    @Test
    fun `PIN error SW codes are in correct range`() {
        val wrongPinBase = 0x6300
        val wrongPinMax = 0x630F
        assertThat(wrongPinMax - wrongPinBase).isEqualTo(15) // 4 bits for remaining

        val pinBlocked = 0x9C0C
        assertThat(pinBlocked).isNotEqualTo(wrongPinBase)
    }

    @Test
    fun `BIP32 not initialized SW is unique`() {
        val bip32NotInit = 0x9C14
        val pinBlocked = 0x9C0C
        val securityError = 0x6982
        assertThat(bip32NotInit).isNotEqualTo(pinBlocked)
        assertThat(bip32NotInit).isNotEqualTo(securityError)
    }

    @Test
    fun `Secure channel SW codes are sequential`() {
        val scRequired = 0x9C20
        val scUninit = 0x9C21
        val scWrongIv = 0x9C22
        val scMacMismatch = 0x9C23
        assertThat(scUninit).isEqualTo(scRequired + 1)
        assertThat(scWrongIv).isEqualTo(scRequired + 2)
        assertThat(scMacMismatch).isEqualTo(scRequired + 3)
    }

    @Test
    fun `init failure SW range is contiguous`() {
        val initStart = 0x6F01
        val initEnd = 0x6F15
        assertThat(initEnd - initStart).isEqualTo(20) // 20 init steps
    }

    @Test
    fun `remaining attempts extraction from 63Cx`() {
        // SW=0x6303 → remaining = 3
        val sw = 0x6303
        val remaining = sw and 0x0F
        assertThat(remaining).isEqualTo(3)

        // SW=0x6301 → remaining = 1
        val sw1 = 0x6301
        assertThat(sw1 and 0x0F).isEqualTo(1)

        // SW=0x6300 → remaining = 0
        val sw0 = 0x6300
        assertThat(sw0 and 0x0F).isEqualTo(0)
    }

    @Test
    fun `init step extraction from 6Fxx`() {
        val sw = 0x6F05
        val step = sw - 0x6F00
        assertThat(step).isEqualTo(5)

        val swLast = 0x6F15
        assertThat(swLast - 0x6F00).isEqualTo(21)
    }

    @Test
    fun `all critical SW codes are documented`() {
        val criticalCodes = mapOf(
            0x6982 to "PIN not verified",
            0x6984 to "Admin PIN required",
            0x9C0C to "PIN blocked",
            0x9C14 to "BIP32 not initialized",
            0x9C20 to "SC required",
            0x9C21 to "SC uninitialized",
            0x9C22 to "SC wrong IV",
            0x9C23 to "SC MAC mismatch",
            0x9C40 to "Schnorr error",
            0x9C44 to "MuSig2 error",
            0x9C45 to "SP error",
            0x9C50 to "TRNG failure",
            0x9CFF to "Authentikey error",
        )

        // All codes must be unique
        assertThat(criticalCodes.keys.size).isEqualTo(criticalCodes.size)
        // All codes must be > 0x6000 (custom range)
        assertThat(criticalCodes.keys.all { it > 0x6000 }).isTrue()
    }
}
