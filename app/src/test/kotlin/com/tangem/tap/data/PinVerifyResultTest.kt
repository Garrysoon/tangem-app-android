package com.tangem.tap.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PinVerifyResultTest {

    @Test
    fun `Success has correct remaining count`() {
        val result = PinVerifyResult.Success(3)
        assertThat(result.remaining).isEqualTo(3)
    }

    @Test
    fun `WrongPin has correct remaining count`() {
        val result = PinVerifyResult.WrongPin(1)
        assertThat(result.remaining).isEqualTo(1)
    }

    @Test
    fun `Blocked is singleton`() {
        val a = PinVerifyResult.Blocked
        val b = PinVerifyResult.Blocked
        assertThat(a).isSameInstanceAs(b)
    }

    @Test
    fun `TagLost is singleton`() {
        val a = PinVerifyResult.TagLost
        val b = PinVerifyResult.TagLost
        assertThat(a).isSameInstanceAs(b)
    }

    @Test
    fun `Error carries message`() {
        val result = PinVerifyResult.Error("test error")
        assertThat(result.message).isEqualTo("test error")
    }

    @Test
    fun `when expression covers all variants`() {
        val results = listOf(
            PinVerifyResult.Success(3),
            PinVerifyResult.WrongPin(1),
            PinVerifyResult.Blocked,
            PinVerifyResult.TagLost,
            PinVerifyResult.Error("err"),
        )

        val labels = results.map { result ->
            when (result) {
                is PinVerifyResult.Success -> "success"
                is PinVerifyResult.WrongPin -> "wrong_pin"
                is PinVerifyResult.Blocked -> "blocked"
                is PinVerifyResult.TagLost -> "tag_lost"
                is PinVerifyResult.Error -> "error"
            }
        }

        assertThat(labels).containsExactly("success", "wrong_pin", "blocked", "tag_lost", "error")
    }
}
