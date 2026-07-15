package com.tangem.tap.data

import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityOSPinRepositoryTest {

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        SecurityOSPinRepository.clearPin()
    }

    @AfterEach
    fun tearDown() {
        SecurityOSPinRepository.clearPin()
        unmockkStatic(Log::class)
    }

    @Test
    fun `getPin returns default 1234 when no pin is set`() {
        val pin = SecurityOSPinRepository.getPin()
        assertThat(pin).isEqualTo("1234".toByteArray())
    }

    @Test
    fun `setPin stores pin for session`() {
        val testPin = byteArrayOf(0x41, 0x42, 0x43, 0x44) // "ABCD"
        SecurityOSPinRepository.setPin(testPin)

        val retrieved = SecurityOSPinRepository.getPin()
        assertThat(retrieved).isEqualTo(testPin)
    }

    @Test
    fun `clearPin removes session pin`() {
        SecurityOSPinRepository.setPin(byteArrayOf(0x01, 0x02))
        SecurityOSPinRepository.clearPin()

        val pin = SecurityOSPinRepository.getPin()
        assertThat(pin).isEqualTo("1234".toByteArray())
    }

    @Test
    fun `hasSessionPin returns true after setPin`() {
        SecurityOSPinRepository.setPin(byteArrayOf(0x01))
        assertThat(SecurityOSPinRepository.hasSessionPin()).isTrue()
    }

    @Test
    fun `hasSessionPin returns false after clearPin`() {
        SecurityOSPinRepository.setPin(byteArrayOf(0x01))
        SecurityOSPinRepository.clearPin()
        assertThat(SecurityOSPinRepository.hasSessionPin()).isFalse()
    }

    @Test
    fun `hasSessionPin returns false initially`() {
        assertThat(SecurityOSPinRepository.hasSessionPin()).isFalse()
    }
}
