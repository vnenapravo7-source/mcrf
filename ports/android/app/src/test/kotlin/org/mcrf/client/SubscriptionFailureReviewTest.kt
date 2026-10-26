package org.mcrf.client

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.MalformedInputException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

/** New diagnostic review cases. Opener throws before any network access. */
class SubscriptionFailureReviewTest {
    @Test fun actualClientKeepsDistinctDnsTlsAndTimeoutCausesWithoutPrivateUrl(){
        for((cause,code) in listOf(UnknownHostException("private-host.invalid") to "SUBSCRIPTION_DNS",
            SSLHandshakeException("private certificate fixture-secret") to "SUBSCRIPTION_TLS",
            SocketTimeoutException("private-host.invalid") to "SUBSCRIPTION_TIMEOUT")){
            try{
                SubscriptionClient.download("https://unit.invalid/private-path?token=fixture-secret",SubscriptionClient.Opener {throw cause})
                fail("Owned opener must fail before network")
            }catch(error:IllegalArgumentException){
                assertSame(cause,error.cause);assertTrue(error.message!!.startsWith(code))
                assertFalse(error.message!!.contains("fixture-secret"));assertFalse(error.message!!.contains("private-host"))
                assertFalse(error.message!!.contains("private-path"))
            }
        }
    }
    @Test fun wrappedTlsAndUtf8RemainRecognizableWithoutDumpingInput(){
        assertTrue(SubscriptionFailure.describe(IllegalArgumentException("wrapper",SSLHandshakeException("secret"))).startsWith("SUBSCRIPTION_TLS"))
        assertTrue(SubscriptionFailure.describe(IllegalArgumentException("wrapper",MalformedInputException(3))).startsWith("SUBSCRIPTION_UTF8"))
    }
    @Test fun onlyExactControlledHttpMessageCanBecomeAStatus(){
        assertTrue(SubscriptionFailure.describe(IllegalArgumentException("Сервер подписки вернул HTTP 403.")).startsWith("SUBSCRIPTION_HTTP_403"))
        assertTrue(SubscriptionFailure.describe(IllegalArgumentException("Сервер подписки вернул HTTP 403. fixture-secret")).startsWith("SUBSCRIPTION_FORMAT"))
        assertFalse(SubscriptionFailure.describe(IOException("raw password fixture-secret")).contains("fixture-secret"))
    }
    @Test fun malformedProviderBodyAndParserSecretsAreNeverLogged(){
        val detail=SubscriptionFailure.describe(IllegalArgumentException("Invalid JSON {password:fixture-secret,token:private} "+"x".repeat(3000)))
        assertEquals("SUBSCRIPTION_FORMAT: Не удалось разобрать подписку. Старые данные сохранены.",detail)
    }
    @Test fun knownBoundsAndRedirectRejectionsKeepSafeSpecificReason(){
        for(message in listOf("Подписка больше 4 МБ.","Некорректное или небезопасное перенаправление подписки.",
            "В подписке нет подключений. Старые данные сохранены."))
            assertEquals("SUBSCRIPTION_REJECTED: $message",SubscriptionFailure.describe(IllegalArgumentException(message)))
    }
    @Test fun cancellationDoesNotMasqueradeAsNetworkOrFormatFailure(){
        for(error in listOf(InterruptedException("private"),CancellationException("private")))
            assertTrue(SubscriptionFailure.describe(error).startsWith("SUBSCRIPTION_CANCELLED"))
    }
    @Test fun cyclicCausesAreFiniteAndDoNotExposeMessage(){
        val one=IOException("fixture-secret");val two=IOException("private",one);one.initCause(two)
        assertEquals("SUBSCRIPTION_NETWORK: Не удалось загрузить подписку из-за ошибки сети.",SubscriptionFailure.describe(one))
    }
    @Test fun writeIoFailureIsNotMisreportedAsNetworkFailureOrGuaranteedRollback(){
        val detail=SubscriptionFailure.describe(IOException("failure after possible commit"),SubscriptionFailure.Phase.SAVE)
        assertTrue(detail.startsWith("SUBSCRIPTION_WRITE_UNCONFIRMED:"));assertFalse(detail.contains("Старые данные сохранены"))
    }
}
